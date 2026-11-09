# Architecture

Goal: Jakarta® Faces support in any LSP editor that comes close to IntelliJ's JSF/EL plugins. The full feature
list is in [intellij-faces-features.md](intellij-faces-features.md).

## Overview

```
Editor
 ├─ editor extension (e.g. vscode-faces)
 │    ├─ Facelets language for *.xhtml: markup grammar with EL embedded
 │    ├─ snippets
 │    ├─ starts faces-ls
 │    └─ adds faces-jdt.jar to jdtls' bundles
 │
 ├─ jdtls (the editor's Java support, e.g. the Red Hat Java extension)
 │    └─ faces-jdt.jar (OSGi bundle loaded into jdtls)
 │         └─ local socket API: Java model queries
 │
 └─ faces-ls (this language server, Java, lsp4j)
      ├─ Facelets / faces-config / *.taglib.xml model, EL parser, taglib registry
      ├─ completion, hover, definition, references, call hierarchy, diagnostics, code actions, semantic tokens
      └─ client of faces-jdt's socket API
```

## Components

### Editor extension

What a client has to provide (see [editors.md](editors.md) for plain LSP clients):

- A **Facelets language** for `*.xhtml` with syntax highlighting for the markup and for EL in attribute values and
  text. faces-ls' semantic tokens classify EL names but leave plain markup to the editor's grammar.
- **Starting faces-ls** with a JDK 21+, passing settings as initialization options.
- **Loading faces-jdt into jdtls** through jdtls' `bundles` initialization option. VS Code's Red Hat Java extension
  does this for every jar an extension lists under `contributes.javaExtensions`. faces-ls finds the bundle through
  the rendezvous directory next to the jar, or through `FACES_JDT_RENDEZVOUS` when the extension sets it.

[vscode-faces](https://github.com/jungm/vscode-faces) bundles both jars, adds a TextMate grammar with EL injected
into HTML, and lists faces-jdt as a Java extension.

### `faces-ls` (language server, `faces-ls/`)

Java 21, lsp4j. Handles Facelets documents; faces-config.xml, `*.taglib.xml` and web.xml are read as project
configuration.

It parses markup with its own lightweight, error-tolerant XML DOM with offsets (`xml/`). lemminx was the
alternative, but its extensions are added to its classpath at startup, so faces-ls could not plug into a lemminx
instance an editor already runs for XML.

Responsibilities:

- **Taglib registry**: tags/attributes from bundled spec taglibs for each Faces version (h, f, ui, cc, c, fn, pt,
  jsf), project `WEB-INF/*.taglib.xml`, `*.taglib.xml` found in classpath jars, and the
  `jakarta.faces.FACELETS_LIBRARIES` context param.
  - Composite components: `resources/<lib>/*.xhtml` in the project and `META-INF/resources/<lib>` in jars, with
    attributes from `<cc:interface>`.
  - Typed attribute values (enums/booleans/paths) are ported from IntelliJ's reference files for each library.
- **EL engine**: its own parser, a scope model for the
  implicit objects and the variables tags declare (`var`, `ui:param`, `f:loadBundle`, `f:importConstants` and similar tags such as `p:importEnum` or `o:importFunctions`,
  `cc.attrs`), and type evaluation through faces-jdt.
- **LSP features**: completion, hover (Javadoc), definition, references, rename, signatureHelp, documentLink (paths),
  publishDiagnostics, codeAction (create property/method, create composite component/attribute, extract composite
  component), semantic tokens.
- **Fallback without jdtls**: tags, attributes, IDs, paths and resource features keep working. EL resolution is
  limited to implicit objects and tag variables, and unresolved-reference diagnostics are turned off.

### `faces-jdt` (jdtls bundle, `faces-jdt/`)

An OSGi bundle depending on `org.eclipse.jdt.ls.core` and `org.eclipse.jdt.core`, with loose version ranges (the
same approach as Qute's `qute.jdt` and LSP4MP). It runs inside jdtls, so it sees the imported Maven/Gradle project,
the full classpath, and unsaved Java edits (jdtls gets `didChange`).

API exposed to faces-ls:

| Query | Purpose |
|---|---|
| `projectInfo(uri)` | Which Java project a file belongs to, Faces version (`jakarta.faces` vs `javax.faces`), source/resource roots, web root |
| `beans(project)` | `@Named` (CDI, incl. stereotypes/producers), `@ManagedBean`, faces-config `<managed-bean>`: name, type, scope, location |
| `resolveType(fqn)` | Members visible to EL: properties (getter/setter/`is`), methods with signatures, generics resolved; supertypes; Map/List/array element types |
| `javadoc(member)` | Hover |
| `location(member)` | Go to definition (also `jdt://` for library classes, which jdtls clients can open as class-file contents) |
| `annotated(project, annotation)` | `@FacesConverter`, `@FacesValidator`, `@FacesComponent`, `@FlowDefinition`, `@ResourceDependency` … |
| `classpathResources(project, glob)` | Taglibs and `META-INF/resources` in jars |
| `createMember(type, spec)` | Workspace edit for the "create property/method" quick fix |
| change notifications | Invalidate faces-ls caches when Java types change |

Beans are searched in the project's sources, in referenced workspace projects (modules) and in library jars. The
results for a jar are cached by path and timestamp and kept across source changes, so only sources are searched
again after an edit. Members of library classes with attached sources are located in a read-only copy of the
source extracted to `faces-jdt-sources/` next to the rendezvous directory.

faces-ls reads `META-INF/faces-config.xml` and `META-INF/*.taglib.xml` from every classpath jar and directory, finds
composite components under `META-INF/resources/`, supports `<composite-library-name>` in taglibs, and looks up
resource bundles in jars. Jar entries it navigates to are extracted into a read-only cache in the temp directory.

Java → EL features:

- **Rename** a getter/method/bean in Java so EL updates too: try a JDT rename participant
  (`org.eclipse.ltk.core.refactoring.renameParticipants`) in faces-jdt. **Not yet verified** that jdtls calls
  participants.
- **Find usages** from Java into `.xhtml`: jdtls' `references` won't include them. faces-ls probably has to be
  registered for the Java language too, next to jdtls, to answer `references` for Java positions.

## Communication between faces-ls and jdtls

Qute (redhat-developer/quarkus-ls) uses the same split: `qute.jdt` registers `delegateCommandHandler` commands
(`qute/template/resolvedJavaType`, …), and `qute.ls` sends them to the **client**, which forwards them to jdtls
through `java.execute.workspaceCommand` (vscode-quarkus `src/qute/languageServer/client.ts`). That needs client
code for every editor, and many clients cannot forward custom requests to another language server at all.

faces-ls uses a side channel instead, which works with any client:

1. When it activates, faces-jdt opens a localhost socket (TCP on 127.0.0.1 with a random port).
2. It writes `{port, token, pid}` to a rendezvous file **next to its own jar** (`faces-jdt-rendezvous/`), keyed
   by the jdtls pid.
3. faces-ls finds the same directory (next to its own jar, from `FACES_JDT_RENDEZVOUS`, or from the
   `jdt_rendezvous` setting), reads the file, connects, and authenticates with the token. JSON-RPC over the socket.
4. faces-ls reconnects if jdtls restarts, and runs in fallback mode until a connection exists.

When several jdtls processes run (several editor windows), faces-ls picks the one whose projects contain its
workspace root.

## Client behaviour the design relies on

- jdtls installs `bundles` with `START_ACTIVATION_POLICY`; faces-jdt declares no lazy activation, so its activator
  runs during jdtls initialization.
- Some editors start jdtls only for Java buffers. When a Facelets page is opened first and no jdtls has announced
  itself, faces-ls asks the user (`window/showMessageRequest`) and opens a Java source with `window/showDocument`.
- Clients that treat `prefix:name` as one word filter completions by all of it, so tag and function items carry
  their prefix.

## Alternative considered: standalone Java model

faces-ls builds its own Java model: classpath from Maven (`dependency:build-classpath`) or the Gradle Tooling API,
bytecode through ASM/ClassGraph, sources through JavaParser.

- Pros: self-contained, no side channel, no jdtls version coupling.
- Cons: indexes everything a second time, doesn't see unsaved Java edits, rename/references into Java are much
  weaker, and Maven/Gradle import logic gets duplicated.

Rejected for now. It may come back as the fallback mode.

## Testing

| Layer | Tool | Runs headless? |
|---|---|---|
| faces-ls | JUnit on fixture documents and projects: completion, hover, definition, diagnostics, semantic tokens, index | yes |
| Distribution with jdtls | `scripts/test-nvim.sh`: the zip in a headless Neovim with jdtls loading faces-jdt, against `samples/multi` | yes |
| Single requests | `scripts/lsp-client.py FILE hover LINE CHARACTER` | yes |

## Facelets index

`FaceletsIndex` holds every Facelets file of the workspace folders (web roots and `META-INF/resources` of all
modules, skipping `target`, `build`, `node_modules` and hidden directories) and of the jars on known classpaths,
with edges for composite usages (including `<composite-library-name>` taglibs, minus tags the taglib declares as
Java components), `ui:include` and templates. Pages are files nobody uses that are neither composites nor below
`WEB-INF`. It is built in the background, replaced as an immutable snapshot, and updated per file from the file
watcher and open documents.

`ClientIds` expands a page through templates (`ui:define` into `ui:insert`), includes and composites and computes
the client ids of components with ids. Results are cached per snapshot; completion uses the previous result while
the new one is computed, so typing does not wait for re-expanding all pages a widely used composite is in.

Measured on a 442-module project with 2,304 Facelets files: index build about 1 s, usage tree of a composite used
in 225 pages 15 ms per level, client ids of all 225 pages about 0.5 s cold and 11 ms cached.

