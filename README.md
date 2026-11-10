# Faces Language Server

A language server for Jakarta® Faces: Facelets pages (`*.xhtml`), Expression Language and their Java backing code.

- **Standalone LSP server.** It speaks plain LSP over stdio, so it works with any LSP client: Neovim, Helix, Emacs,
  Sublime Text and others (setup in [docs/editors.md](docs/editors.md)). The main consumer is VS Code, through the
  extension in [vscode/](vscode).
- **Tag libraries are discovered, not bundled.** Tags, attributes and composite components are read from the
  project and its classpath: every `*.taglib.xml`, composite component and `faces-config.xml` in your modules and
  jars. PrimeFaces, OmniFaces or your in-house libraries? They all get the same first-class support at any version.
- **Built for Faces 4.** The standard tags (`h`, `f`, `ui`, ...) are those of Faces 4.1. Older `javax.faces`
  projects and their `xmlns.jcp.org` / `java.sun.com` namespaces work too, but are offered the 4.1 tag set.

Java types, beans and the classpath come from jdtls, which loads a small bundle (`faces-jdt`) from this project.
Facelets is the only supported view declaration language.

## Features

- Completion, hover and go to definition for tags, attributes, EL (beans, properties, methods, bundle keys,
  tag variables), template and include paths, component ids and search expressions (`@form`, `@parent`, ...)
- Client ids across files: pages are expanded through templates, includes and composites
- Diagnostics for unknown tags and attributes, missing required attributes, EL errors and unresolved names
- Find references and call hierarchy for composites, includes and templates, and Facelets usages of Java members
- Code actions to declare namespaces and add required attributes; semantic tokens for EL

## Building and testing

Requires JDK 21+ and Maven.

```sh
mvn package   # faces-ls.jar, faces-jdt.jar and faces-ls-dist/target/faces-ls-<version>.zip
mvn verify
```

The VS Code extension is built in [vscode/](vscode/README.md#development). The structure of the code is described in
[docs/architecture.md](docs/architecture.md), sample projects are in `samples/`.

## License

Apache License 2.0. The bundled standard tag library descriptors in
`faces-ls/src/main/resources/io/github/jungm/fls/taglibs` come from Eclipse Mojarra and are licensed under
EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 (see the NOTICE there).

Jakarta is a registered trademark of the Eclipse Foundation. This project is not affiliated with or endorsed by the
Eclipse Foundation.
