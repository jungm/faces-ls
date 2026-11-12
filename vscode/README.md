# Faces for Jakarta® EE

Jakarta® Faces support for VS Code: Facelets pages (`*.xhtml`), Expression Language and their Java beans, powered
by the [Faces Language Server](https://github.com/jungm/faces-ls).

## Features

- Completion, hover and go to definition for tags, attributes and EL: beans, properties, methods, bundle keys,
  taglib functions
- Tags come from the tag libraries and composite components of your project and its jars, so PrimeFaces,
  OmniFaces and your own components need no setup
- Navigation through templates, includes, composite components, component ids and search expressions like `@form`
- Diagnostics for unknown tags and attributes, missing required attributes, EL errors and unresolved names
- Find references and call hierarchy, including the pages that use a Java method or property
- Quick fixes to declare namespaces and add required attributes, snippets for common tags

## Requirements

- Java 21 or newer, from `faces.java.home`, `JAVA_HOME`, the Red Hat Java extension or `PATH`
- [Language Support for Java by Red Hat](https://marketplace.visualstudio.com/items?itemName=redhat.java) for
  everything Java: beans, properties, methods, library jars. Without it, only tags, ids, paths and resource bundles
  work.

## Tips

- Declare missing namespaces on save:
  ```json
  "[facelets]": { "editor.codeActionsOnSave": { "source.addMissingImports": "explicit" } }
  ```
- Predefine prefixes for other libraries with `faces.namespaces`, e.g. `{"o": "http://omnifaces.org/ui"}`.
- If another extension claims `*.xhtml`, set `"files.associations": { "*.xhtml": "facelets" }`.

## License

Apache License 2.0. The bundled standard tag library descriptors come from Eclipse Mojarra and are licensed under
EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 (see `NOTICE-taglibs.md`).

Jakarta is a registered trademark of the Eclipse Foundation. This project is not affiliated with or endorsed by the
Eclipse Foundation.
