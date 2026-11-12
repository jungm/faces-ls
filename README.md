# Faces Language Server

A language server for Jakarta® Faces: Facelets pages (`*.xhtml`), Expression Language and their Java backing code.
For VS Code, install the extension from the
[Marketplace](https://marketplace.visualstudio.com/items?itemName=jungm.faces) or
[Open VSX](https://open-vsx.org/extension/jungm/faces) (VSCodium, Cursor and others); other editors talk to the
server over stdio ([setup](docs/editors.md)).

Tags are read from the project and its classpath (`*.taglib.xml`, composite components, `faces-config.xml`), so any
component library works. Only the Faces 4.1 standard tags are built in; `javax.faces` projects work too, but get the
4.1 tags. Java types and the classpath come from jdtls, through the `faces-jdt` bundle. Facelets is the only supported
view declaration language.

## Building

Requires JDK 21+, Maven and, for the extension, Node.js 20+.

```sh
mvn verify                  # faces-ls.jar, faces-jdt.jar and faces-ls-dist/target/faces-ls-<version>.zip

cd vscode
npm install
npm run server              # copies the jars into server/ (or: npm run server -- faces-ls-<version>.zip)
npm run build
npm run package             # faces-<version>.vsix
```

`Run Extension` (F5, with the repository root opened) starts VS Code on `samples/demo`. The code structure is
described in [docs/architecture.md](docs/architecture.md).

## License

Apache License 2.0. The standard tag library descriptors in `faces-ls/src/main/resources/io/github/jungm/fls/taglibs`
come from Eclipse Mojarra and are licensed under EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 (see the NOTICE
there).

Jakarta is a registered trademark of the Eclipse Foundation. This project is not affiliated with or endorsed by the
Eclipse Foundation.
