# Faces Language Server

A language server for Jakarta® Faces: Facelets pages (`*.xhtml`), Expression Language and Java integration through
jdtls. It works with any LSP client.

- VS Code: [vscode-faces](https://github.com/jungm/vscode-faces)
- Neovim, Helix and other LSP clients: see [docs/editors.md](docs/editors.md)

## Features

- **Completion**
  - tags of every declared library and of composite component libraries; standard libraries that are not
    declared yet are offered too, and completing one adds its `xmlns` declaration
  - attributes, with required ones first
  - attribute values: namespace URIs, template/include paths, component ids for `for`/`render`/`execute`,
    `f:ajax` events, class names for `type` attributes
  - EL: implicit objects, CDI `@Named` beans, managed beans, resource bundle variables, tag variables (`var`,
    `varStatus`, `ui:param`, `c:set`, `f:importConstants`, `p:importEnum` and the like), lambda parameters, `cc`/`cc.attrs`, bean properties and
    methods (with generics, e.g. `List<User>` iteration), resource bundle keys, taglib functions
- **Hover**: tag and attribute documentation, EL types with Javadoc, bundle values.
- **Go to definition**: EL beans, properties and methods to Java source; bundle keys to `.properties`; composite tags
  and attributes to the component file; `template`/`src` paths; `ui:define` to the matching `ui:insert`; ids.
- **Component search expressions** in `update`, `process`, `render`, `execute`, `for` (and PrimeFaces' `appendTo`,
  `target`, ...): go to definition and hover resolve `@this`, `@parent`, `@form`, `@namingcontainer`, `@composite`,
  `@child(n)`, `@next`, `@previous`, `@root`, `@id(..)`, `@widgetVar(..)`, relative and absolute ids and chains like
  `@form:@child(0)` against the page's component tree; completion offers keywords, ids and widgetVar names.
- **Where a file is used**: a workspace-wide index of all Facelets files (every module's web root and
  `META-INF/resources`, plus library jars) follows composite components, `ui:include` and templates. Incoming
  calls in the call hierarchy show who uses a composite, include or template, level by level up to the pages;
  find references lists all usages; a code lens shows "used in N files · M pages".
- **Client ids across files**: pages are expanded through templates, includes and composites, so `update`,
  `process`, ... complete absolute client ids such as `:mainForm:angaben:person:street` of every page the current
  file ends up in, and go to definition / hover resolve them into the file that declares the component. Ids below
  naming containers without (or with EL) ids and inside iterating components are left out, as their client ids
  are generated or per row.
- **Diagnostics**: unknown tags and attributes, missing required attributes, undeclared prefixes, unknown tag
  libraries, EL syntax errors, unresolved variables, properties, methods, functions, bundle keys and composite
  attributes.
- **Code actions**: declare missing namespaces (quick fix, or `source.addMissingImports` for the whole file).
- **Semantic tokens**: EL names are classified by what they resolve to (CDI beans, tag variables, implicit objects,
  lambda parameters, properties, methods), deprecated members are marked, and composite component tags and
  component ids with the search expressions that reference them are marked too.

Beans, composite components, tag libraries, faces-config.xml files and resource bundles are picked up from the
project, from other modules of a multi-module build and from library jars. Navigation into a jar opens a read-only
extracted copy (Java classes need a sources jar, e.g. `-sources.jar` next to the jar in `~/.m2`).

## How it works

See [docs/architecture.md](docs/architecture.md). In short:

- `faces-ls/` is the language server (Java 21, lsp4j).
- `faces-jdt/` is an OSGi bundle running inside jdtls. It answers Java model queries over a loopback socket
  announced in a rendezvous file next to its jar. Without jdtls everything except the Java-backed EL features
  still works.
- `faces-ls-dist/` builds `faces-ls-<version>.zip` with launchers and both jars, for LSP clients without an
  extension of their own.

## Building

Requirements: JDK 21+, Maven.

```sh
mvn package     # faces-ls/target/faces-ls.jar, faces-jdt/target/faces-jdt.jar, faces-ls-dist/target/faces-ls-<version>.zip
```

## Testing

```sh
mvn test
scripts/test-nvim.sh                                     # the distribution in a headless Neovim against samples/multi
scripts/lsp-client.py FILE hover LINE CHARACTER          # single requests to faces-ls
```

`samples/demo` is a small project to try, `samples/multi` a multi-module one (`web` uses beans, composites and a
bundle from `shared`, and PrimeFaces).

## License

Apache License 2.0. The bundled standard tag library descriptors in
`faces-ls/src/main/resources/io/github/jungm/fls/taglibs` come from Eclipse Mojarra and are licensed under
EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 (see the NOTICE there).

Jakarta is a registered trademark of the Eclipse Foundation. This project is not affiliated with or endorsed by the
Eclipse Foundation.
