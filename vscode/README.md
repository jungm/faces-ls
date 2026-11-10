# Faces for Jakarta® EE

Jakarta® Faces support for Visual Studio Code: Facelets pages (`*.xhtml`), Expression Language and Java
integration, powered by the [Faces Language Server](https://github.com/jungm/faces-support).

## Features

- **Facelets language** for `*.xhtml`, highlighted as HTML with EL (`#{…}`, `${…}`) in attribute values, text,
  `<script>` and `<style>`.
- **Completion** of tags, attributes, attribute values (namespaces, paths, component ids, events) and EL (implicit
  objects, CDI beans, tag variables, bean properties and methods, resource bundle keys, taglib functions). Completing
  a tag of an undeclared standard library adds its `xmlns` declaration.
- **Hover** with tag and attribute documentation, EL types with Javadoc and bundle values.
- **Go to definition** from EL into Java sources and `.properties` files, from composite tags to the component,
  from `template`/`src` paths, `ui:define` and component ids, including search expressions such as
  `@form:@child(0)` and absolute client ids across templates, includes and composites.
- **Find references**, **call hierarchy** and a **code lens** showing where composites, includes and templates
  are used.
- **Facelets usages of Java members**: "Find All References" and "Show Call Hierarchy" on a Java method, getter,
  setter or constant list the `.xhtml` files whose EL uses it next to the Java usages (needs the Red Hat Java
  extension).
- **Diagnostics** for unknown tags and attributes, missing required attributes, undeclared prefixes, EL syntax
  errors and unresolved beans, properties, methods, functions and bundle keys.
- **Quick fixes** to declare an undeclared prefix that is predefined in `faces.namespaces` (also for the whole file
  as the `source.addMissingImports` code action) and to add missing required attributes.
- **Semantic highlighting** of EL names by what they resolve to (beans, tag variables, implicit objects,
  properties, methods, deprecated members).
- **Snippets** for page skeletons, template clients, composite components and common tags.

## Requirements

- Java 21 or newer to run the language server. It is looked up in `faces.java.home`, `JAVA_HOME`, the runtime
  bundled with the Red Hat Java extension, then `java` on `PATH`.
- The [Language Support for Java by Red Hat](https://marketplace.visualstudio.com/items?itemName=redhat.java)
  extension for the Java-backed features (beans, properties, methods, Javadoc, navigation into Java, and
  everything from library jars and other modules, since the classpath comes from it). It loads the language
  server's `faces-jdt` bundle into jdtls. Without it, tags and attributes of the standard and project tag
  libraries, the project's composite components and resource bundles, ids and paths still work.

## Settings

| Setting | Meaning |
|---|---|
| `faces.java.home` | JDK 21+ that runs the language server |
| `faces.namespaces` | Predefined namespace prefixes, e.g. `{"o": ["omnifaces", "http://omnifaces.org/ui"]}`; an empty string removes a built-in one |
| `faces.imports` | Tags that import a Java class into EL, by namespace URI and tag name, e.g. `{"http://example.org/ui": {"importEnum": "enum"}}` (`constants`, `enum` or `functions`; an empty string removes a built-in one) |
| `faces.server.jar` | A `faces-ls.jar` to use instead of the bundled one |
| `faces.server.vmargs` | JVM arguments for the language server |
| `faces.server.logFile` | Debug log of the language server |
| `faces.trace.server` | Trace of the LSP communication in the output channel |

Commands: **Faces: Restart Language Server** (`faces.restartServer`) and **Faces: Show Language Server Output**
(`faces.showOutput`). After a change to `faces.java.home` or a `faces.server.*` setting, the extension offers to restart the server.

To declare missing namespaces on save:

```json
"[facelets]": {
  "editor.codeActionsOnSave": { "source.addMissingImports": "explicit" }
}
```

If another extension claims `*.xhtml`, set `"files.associations": { "*.xhtml": "facelets" }`.

## Development

Requirements: Node.js 20+, and the language server built with `mvn package` in the repository root.

```sh
npm install
npm run server      # copies faces-ls.jar and faces-jdt.jar into server/ (or: npm run server -- faces-ls-<version>.zip)
npm run build       # bundles src/ into dist/extension.js
npm run package     # faces-<version>.vsix
```

`Run Extension` (F5, with the repository root opened in VS Code) starts an Extension Development Host on
`samples/demo`.
The language server is bundled into the extension, so a released extension always runs the server version it
was built with.

## License

Apache License 2.0. The bundled language server contains standard tag library descriptors from Eclipse Mojarra,
licensed under EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 (see `NOTICE-taglibs.md`).

Jakarta is a registered trademark of the Eclipse Foundation. This project is not affiliated with or endorsed by the
Eclipse Foundation.
