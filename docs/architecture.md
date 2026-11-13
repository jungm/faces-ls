# Architecture

Jakarta® Faces support for LSP editors. [intellij-faces-features.md](intellij-faces-features.md) lists what
IntelliJ's JSF/EL plugins offer and how each feature maps onto this design.

Facelets is the only view declaration language in scope, by design: every page model, completion, diagnostic and
semantic token is computed on Facelets markup. Other view declaration languages a Faces application plugs in are
not supported.

## Overview

```
Editor
 ├─ editor extension (e.g. the VS Code extension in vscode/)
 │    ├─ Facelets language for *.xhtml: markup grammar with EL embedded
 │    ├─ snippets
 │    ├─ starts faces-ls
 │    └─ adds faces-jdt.jar to jdtls' bundles
 │
 ├─ jdtls (the editor's Java support, e.g. the Red Hat Java extension)
 │    └─ faces-jdt.jar (OSGi bundle loaded into jdtls)
 │         └─ JSON-RPC on a loopback socket: Java model queries
 │
 └─ faces-ls (this language server, Java 21, lsp4j)
      ├─ Facelets / faces-config / *.taglib.xml model, EL parser, taglib registry, Facelets index
      ├─ completion, hover, definition, references, call hierarchy, code lens, diagnostics, code actions,
      │  semantic tokens
      └─ client of faces-jdt's socket
```

## Components

### Editor extension

What a client has to provide (see [editors.md](editors.md) for plain LSP clients):

- A **Facelets language** for `*.xhtml` with syntax highlighting for the markup and for EL in attribute values and
  text. faces-ls' semantic tokens classify EL and a few markup names but leave plain markup to the editor's grammar.
- **Starting faces-ls** with Java 21+, passing settings as initialization options.
- **Loading faces-jdt into jdtls** through jdtls' `bundles` initialization option. VS Code's Red Hat Java extension
  does this for every jar an extension lists under `contributes.javaExtensions`.

The [VS Code extension](../vscode) bundles both jars, adds a TextMate grammar with EL injected into HTML, lists
faces-jdt as a Java extension and sets `FACES_JDT_RENDEZVOUS` for faces-ls.

### `faces-ls` (language server, `faces-ls/`)

Java 21, lsp4j, stdio. It serves Facelets documents; faces-config.xml, `*.taglib.xml`, web.xml and `.properties`
files are read as project configuration and watched through `workspace/didChangeWatchedFiles`.

Markup is parsed by its own error-tolerant XML DOM with offsets (`xml/`). lemminx is not used: its extensions are
added to its classpath at startup, so faces-ls could not plug into a lemminx instance an editor already runs.

Projects are the directories with a `pom.xml`, `build.gradle` or `build.gradle.kts`. Source folders and the
classpath of a project come from faces-jdt; without it, `src/main/java`, `src/main/resources` or `src` are used as
source folders and the classpath is empty.

- **Taglib registry**: the standard libraries from bundled Jakarta Faces 4.1 taglib descriptors (h, f, ui, cc, c,
  fn, pt, jsf), `*.taglib.xml` below `WEB-INF` and `META-INF` of the project, the
  `jakarta.faces.FACELETS_LIBRARIES` context param, and `META-INF/*.taglib.xml` of classpath jars and
  directories. `<composite-library-name>` in a taglib is supported.
  - Composite components: `resources/<lib>/*.xhtml` of web roots, `META-INF/resources/<lib>` of source folders and
    jars, with attributes from `<cc:interface>`.
  - Attribute value completion: namespace URIs, view paths, component ids and search expressions, `f:ajax`
    events, Java class names for attributes that take a class (through faces-jdt), `true`/`false` for boolean
    attributes.
- **Passthrough elements** (`features/PassthroughElements.java`): a plain HTML element with an attribute in the
  `jakarta.faces` namespace (`<input type="email" jsf:value="...">`) is resolved to the tag the default
  `TagDecorator` of the specification maps it to (`h:inputText`), or `jsf:element` when no row of the table matches.
  Its `jsf:` attributes are attributes of that tag for completion, hover, definition and diagnostics; the other
  attributes are passed through to the markup, so the component id is `jsf:id` only. `jsf:element` additionally
  takes `id`, `rendered` and `binding`, which its taglib leaves out. Where Mojarra and MyFaces differ, the
  specification and MyFaces decide (`<input type="button">` is `h:commandButton`).
- **Resources** (`project/Resources.java`): go to definition on what the `ResourceHandler` serves: `name` and
  `library` of tags that take both (`h:graphicImage`, `h:outputScript`, `h:outputStylesheet`, and the like of
  component libraries, also as `jsf:` attributes of `<img>`, `<script>` and `<link>`), string keys on a
  `ResourceHandler` such as `#{resource['library:name']}` (`this` is the library of the current composite or the
  contract of the current template), the URL in `value`/`url` of `h:graphicImage`, and the contracts of `f:view
  contracts` (their top-level templates, else the marker file). Resources are looked up as the specification packages
  them: `resources/` of web roots (or `WEBAPP_RESOURCES_DIRECTORY`), `META-INF/resources/` of source folders,
  classpath directories and jars (extracted), then the same inside every resource library contract (`contracts/`,
  `WEBAPP_CONTRACTS_DIRECTORY`, `META-INF/contracts/`). The highest library and resource version wins; resources
  are looked up without a locale prefix first, then below each directory named like a locale.
- **Project configuration**: `WEB-INF/faces-config.xml` of web roots, `META-INF/*faces-config.xml` of source
  folders, classpath directories and jars (resource bundles and `<managed-bean>` entries), web.xml context params.
- **Web roots** (`project/WebRoots.java`): `src/main/webapp`, `WebContent`, `web`, `webapp` and
  `src/main/resources/META-INF/resources` below a project root when they are web roots (`META-INF/resources`,
  `src/main/webapp`, or a directory containing `WEB-INF`); when none of them is, every directory up to four
  levels below the project root that contains `WEB-INF`, outside `target`, `build`, `node_modules`, `bin`, `out`
  and hidden directories.
- **EL engine**: its own lexer and parser, a scope model for the implicit objects and the variables tags declare
  (`var`, `varStatus`, `ui:param`, `c:set`, `f:loadBundle`, `cc`/`cc.attrs`, lambda parameters, and class imports
  such as `f:importConstants`, `p:importEnum` or `o:importFunctions`, configurable through the `imports` setting),
  and type evaluation through faces-jdt's type descriptions.
- **Beans**: from faces-jdt (below) plus `<managed-bean>` entries of faces-config files.
- **LSP features** (`ServerCapabilities` in `FacesLanguageServer.initialize`): completion, hover, definition,
  references, call hierarchy, code lens, semantic tokens (full and range), publishDiagnostics, and code actions
  of kind `quickfix` (declare an undeclared prefix that is predefined in the `namespaces` setting, add missing
  required attributes) and `source.addMissingImports` (declare all such prefixes).
- **Without faces-jdt**: tags and attributes of the standard and project taglibs, project composite components,
  project resource bundles, ids, search expressions and paths work. Everything that needs the Java model or the
  classpath does not: CDI beans, properties and methods, Javadoc, navigation into Java, taglibs, composites and
  bundles of jars. Diagnostics for unresolved EL variables and unknown tag libraries are turned off.

### `faces-jdt` (jdtls bundle, `faces-jdt/`)

An OSGi bundle (`Require-Bundle` without version ranges on `org.eclipse.jdt.core`, `org.eclipse.core.resources`,
`org.eclipse.lsp4j.jsonrpc`, Gson, and optionally `org.eclipse.jdt.ls.core` and `org.eclipse.lsp4j`). It runs
inside jdtls, so it uses the projects jdtls has imported from Maven/Gradle and their resolved classpath.

Requests (`FacesJdtService.java`; each query takes `path`, a file of the project, besides its own parameters):

| Request | Answer |
|---|---|
| `authenticate {token}` | `{pid, bundleVersion}`; has to be the first request |
| `ping` | `{pid, projects: [{name, location}]}`: the Java projects jdtls has imported |
| `project` | `{name, location, sourceFolders, classpath}` of the project containing `path` (classpath: jars and the source folders of referenced projects) |
| `beans` | `[{name, type, kind, scope?, location, library?}]`: the named beans visible in the project |
| `type {fqn}` | Descriptions of the type and all its supertypes: kind, type parameters, supertypes, methods (parameters, return type, static, varargs, deprecated), fields, record components. faces-ls derives EL properties from the methods |
| `location {fqn, kind, name?, paramCount}` | `{uri, range}` of a type's, method's or field's name |
| `javadoc {fqn, kind, name?, paramCount}` | `{markdown}` |
| `findTypes {prefix, limit?}` | `[{fqn, simpleName}]` of public types |
| `callHierarchy/prepare`, `callHierarchy/incoming`, `callHierarchy/outgoing` | jdtls' own call hierarchy items and calls |
| `member {uri, line, character}` | `{declaringType, name, kind, parameterCount}` of the member declared or used at a position of a Java file |

Notification to faces-ls (`FacesJdtEvents.java`): `javaChanged`. `JavaChangeListener` watches `POST_CHANGE` deltas
of the Java model and skips deltas that only open a working copy or only touch non-Java resources. A relevant
delta clears faces-jdt's type and bean caches at once; `javaChanged` follows once deltas have stopped for 400 ms,
and only to connections that hold answers the change may have outdated. faces-ls then drops its Java caches and
republishes diagnostics.

Beans (`JavaModelService.beans`): types, methods and fields annotated with `@Named`, `@Model` (both
`jakarta.*` and `javax.*`) or `@ManagedBean` (`jakarta.faces.bean`/`javax.faces.bean`). The name is the
annotation's value, else the decapitalized class name, the property name of a method, or the field name. The scope
is a scope annotation (`@RequestScoped`, `@ViewScoped`, ...) on the member or its declaring type. Other
stereotypes and producers without `@Named` are not found. The search covers the project's sources, referenced
workspace projects (modules) and library jars (not the JRE); results for a jar are cached by path, modification
time and size and kept across source changes.

Library members with attached sources are located in a read-only copy of the source extracted to
`faces-jdt-sources/<jar name>/<package path>/<Type>.java` next to faces-jdt.jar, since editors cannot open files
inside jars. Members without sources have no location.

## Communication between faces-ls and jdtls

Qute (redhat-developer/quarkus-ls) uses the same split: `qute.jdt` registers `delegateCommandHandler` commands and
`qute.ls` sends them to the **client**, which forwards them to jdtls through `java.execute.workspaceCommand`. That
needs client code for every editor. faces-ls uses a side channel instead, which works with any client:

1. When jdtls starts the bundle, `FacesJdtActivator` opens a server socket on the loopback address with a random
   port and writes `{pid, port, token, bundleVersion, started}` to `jdtls-<pid>.json` in `faces-jdt-rendezvous/`
   next to faces-jdt.jar. Files of jdtls processes that no longer run are deleted.
2. faces-ls takes the rendezvous directory from the `jdt_rendezvous` setting, the `FACES_JDT_RENDEZVOUS`
   environment variable, or `faces-jdt-rendezvous/` next to its own jar when faces-jdt.jar sits there too. Without
   any of them the Java integration is off.
3. `JdtClient` reads the files of live jdtls processes (newest first), connects, and speaks JSON-RPC with lsp4j's
   `Launcher` (LSP base protocol framing) over the socket. The first request is `authenticate` with the token; a
   wrong token, or any other request first, closes the connection.
4. It sends `ping` and keeps the connection whose projects contain a workspace root. If no jdtls serves the
   workspace yet, it stays connected to the newest one and checks again.
5. A background loop reconnects when the connection ends or the jdtls process exits. faces-ls sends the
   `faces/javaStatus` notification (`{"connected": boolean}`) to the client whenever the connection changes.

## Facelets usages of Java members

faces-ls answers `references`, `prepareCallHierarchy` and incoming/outgoing calls for `.java` and `jdt:` URIs.
`JavaUsages` finds the Facelets files whose EL uses a member: properties through getter or setter, method
expressions and calls, imported constants, and functions (`o:importFunctions`, taglib functions).

- References: faces-jdt's `member` names the member at the position (`codeSelect`, so on its declaration or a
  use), and faces-ls answers its EL usages only. Clients merge them with jdtls' references.
- Call hierarchy: faces-jdt runs jdtls' `CallHierarchyHandler` (internal to jdtls, called reflectively) and adds
  the member each item denotes to its `data`; for incoming calls faces-ls appends the Facelets files. Clients ask
  one call hierarchy provider per document, so they have to send Java call hierarchy requests to faces-ls instead
  of jdtls while `faces/javaStatus` reports `connected`.

A source file of a nested module also belongs to each enclosing project in the Eclipse workspace (the aggregator
of a multi-module build); faces-jdt takes the copy that exists in the Java model. The VS Code extension registers
both providers for Java documents.

## Facelets index

`FaceletsIndex` holds every `*.xhtml` file below the workspace folders (skipping `target`, `build`,
`node_modules`, `bin`, `out` and hidden directories) and below `META-INF/resources` of the jars on known
classpaths, with edges for composite usages (including `<composite-library-name>` taglibs, minus tags the taglib
declares as Java components), `ui:include` and templates. Files are placed below their web roots by `WebRoots`;
files outside web roots get no web path. Pages are files nobody uses that are neither composites nor below
`WEB-INF`.

The index is an immutable snapshot. It is built in full at startup, when a `*.taglib.xml` or web.xml changes, and
when a project adds jars. A changed `.xhtml` file (from the file watcher, or an open document with its unsaved
text) produces a new snapshot that re-parses only that file and relinks only the files whose references it can
affect.

`ClientIds` expands a page through templates (`ui:define` into `ui:insert`), includes and composites and computes
the client ids of components with ids. Results are cached per snapshot; completion uses the previous result until
the client ids are recomputed for the new snapshot.

Measured on a 442-module project with 2,304 Facelets files: index build about 1 s, usage tree of a composite used
in 225 pages 15 ms per level, client ids of all 225 pages about 0.5 s cold and 11 ms cached.

## Client behaviour the design relies on

- jdtls starts the bundles of its `bundles` option eagerly, so faces-jdt's activator runs while jdtls initializes.
- Some editors start jdtls only for Java buffers. When Facelets documents are open, no jdtls is connected 12 s
  after `initialized` and a rendezvous directory is known, faces-ls asks the user (`window/showMessageRequest`) and
  opens a Java source below `src/main/java` with `window/showDocument`.
- Clients that treat `prefix:name` as one word filter completions by all of it, so tag and function items carry
  their prefix in the filter text.

## Testing

| What | How | Needs |
|---|---|---|
| faces-ls, faces-jdt | `mvn verify` (JUnit: features on fixture documents and projects with a fake faces-jdt, index, parsers, search expressions, semantic tokens; faces-jdt's server, change listener, Javadoc) | JDK 21 |
| Distribution with jdtls | `scripts/test-nvim.sh`: the zip from `faces-ls-dist/target` in a headless Neovim with jdtls loading faces-jdt, against `samples/multi/web` | `mvn package` first, Neovim 0.11+, a jdtls install (`JDTLS`, default: Mason's), and `FACES_TEST_JAVA_HOME` (default: SDKMAN's current Java) for both servers |
| Single requests | `scripts/lsp-client.py FILE REQUEST LINE CHARACTER` with `completion`, `hover` or `definition` | `faces-ls/target/faces-ls.jar` |

`scripts/test-nvim.sh` runs faces-ls and jdtls with the same Java, so it has to be the version the jdtls install
needs: current jdtls releases need Java 25, while building and faces-ls need 21.

## Not implemented

Ideas that the code does not contain:

- **Rename** of a Java property, method or bean that updates EL, possibly through a JDT rename participant
  (`org.eclipse.ltk.core.refactoring.renameParticipants`) in faces-jdt; whether jdtls calls participants is
  unverified. faces-ls has no `rename` provider.
- **Find references from Java into `.xhtml`**: faces-ls answers `references` for Facelets documents only. A client
  could merge faces-ls' answer for Java positions into jdtls' (VS Code merges references of all providers).
- **Resources beyond go to definition**: completion of `library` and `name`, diagnostics for missing resources,
  `#{resource[...]}` in CSS resources (faces-ls serves Facelets documents only), `@ResourceDependency` in Java, and
  `WEBAPP_RESOURCES_DIRECTORY` for composite component discovery (which still assumes `resources/`).
- **signatureHelp, documentLink**, and code actions to create a property or method, create a composite component
  or attribute, or extract a composite component.
- **A standalone Java model** in faces-ls (classpath from Maven/Gradle, bytecode through ASM, sources through
  JavaParser) as an alternative to faces-jdt: self-contained, but it indexes everything a second time, does not
  see unsaved Java edits and duplicates the Maven/Gradle import.
