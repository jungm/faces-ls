# faces-ls: a language server for Jakarta® Faces

faces-ls is a language server for Facelets pages (`*.xhtml`) and Jakarta Expression Language. It works with any
LSP client. For VS Code, use the Faces extension from the
[Marketplace](https://marketplace.visualstudio.com/items?itemName=jungm.faces) or
[Open VSX](https://open-vsx.org/extension/jungm/faces), which bundles faces-ls.

It provides completion, hover, go to definition, diagnostics and code actions for tags, attributes and EL. EL type
information (beans, properties, methods, Javadoc, navigation into Java) comes from **faces-jdt**, a bundle that runs
inside the Eclipse Java language server (jdtls). jdtls also supplies the project's classpath. Without it, tags and
attributes of the standard and project tag libraries, the project's composite components and resource bundles,
ids and paths still work; libraries in jars are not seen.

## Contents of the distribution

```
bin/faces-ls        launcher (Linux, macOS)
bin/faces-ls.cmd    launcher (Windows)
lib/faces-ls.jar    the language server
lib/faces-jdt.jar   the jdtls bundle
```

Requirements: Java 21 or newer (`JAVA_HOME`, or `java` on `PATH`). Extra JVM options go into `FACES_LS_JAVA_OPTS`.

## Setup in any editor

1. Start `bin/faces-ls` for Facelets files (stdio, no arguments). Most editors give `*.xhtml` the filetype `xhtml`.
2. Add `lib/faces-jdt.jar` to the `bundles` initialization option of jdtls. jdtls loads it at startup and announces
   it in `lib/faces-jdt-rendezvous/`, where faces-ls finds it. Keep both jars in the same directory.
3. Open a Java file once per session so the editor starts jdtls (faces-ls offers to open one when no jdtls has
   connected).

jdtls runs on its own Java runtime, which has to be the version the jdtls release needs (Java 25 for current
releases); faces-jdt runs inside it.

### Semantic tokens

faces-ls provides `textDocument/semanticTokens/full` and `/range` and asks the client to refresh them once beans or
Java types have loaded. EL is tokenized completely (delimiters, keywords, literals and operators included); in the
markup only what the syntax cannot tell is marked, so editors should layer the tokens over their own XML or HTML
highlighting.

| Token | Meaning |
|---|---|
| `variable` | tag variables (`var`, `varStatus`), `ui:param`, `c:set`; unresolved names |
| `variable` + `static` | CDI and managed beans, resource bundle variables |
| `variable` + `defaultLibrary` | implicit objects (`facesContext`, `param`, ...), `cc` |
| `parameter` | lambda parameters (`+ declaration` where declared) |
| `property`, `method` | bean properties (`+ readonly` without setter) and methods, `+ deprecated` as in Java |
| `class`, `enumMember` | classes imported with `f:importConstants` and the like, and their enum constants (other constants: `property + static + readonly`) |
| `namespace`, `function` | taglib functions (`fn:length`; `+ defaultLibrary` for standard libraries); tag prefixes |
| `class` | composite component tag names |
| `variable` + `declaration` | component ids and `widgetVar` names; ids in `render`/`update`/`for` are `variable` |
| `keyword`, `string`, `number`, `operator` | the rest of EL, and search expression keywords (`@form`) |

### Settings

Pass settings as initialization options (as is, or wrapped as `{"settings": {...}}`). `namespaces` and `imports`
can also be changed with `workspace/didChangeConfiguration` (as is, or under a `faces` or `faces-ls` key);
`log_file` and `jdt_rendezvous` are read at initialization only.

| Setting | Meaning |
|---|---|
| `namespaces` | Predefined prefixes, e.g. `{"p": "primefaces", "o": ["omnifaces", "http://omnifaces.org/ui"]}`. Tags and functions of an undeclared predefined prefix are completed and its `xmlns` declaration added. Built in: `h`, `f`, `ui`, `cc`, `c`, `fn`, `pt`, `jsf`, `p`. An empty string removes one. |
| `imports` | Tags that import a Java class into EL, by namespace URI and tag name, e.g. `{"http://example.org/ui": {"importEnum": "enum"}}`. The tag takes the class in `type` and the EL name in `var` (default: the simple class name). `constants` exposes static fields, `enum` enum constants plus `ALL_VALUES`, `functions` static methods as `var:method(...)`. Built in: `f:importConstants`, `p:importConstants`, `p:importEnum`, `o:importConstants`, `o:importFunctions`. An empty string or `null` removes one. |
| `log_file` | Write a debug log to this file. |
| `jdt_rendezvous` | Directory where faces-jdt announces itself, when the jars are not next to each other. |

faces-ls also provides call hierarchy (who uses a composite, include or template), references and a code lens
with usage counts, based on an index of all Facelets files in the workspace folders.

For Java files, faces-ls answers references and the call hierarchy too, while it reports `{"connected": true}` in
the `faces/javaStatus` notification. `textDocument/references` on a Java member answers the EL usages of it in
Facelets files; send it to faces-ls besides jdtls and show both answers. The call hierarchy is jdtls' own (through
faces-jdt) plus the Facelets files whose EL uses the member; send `textDocument/prepareCallHierarchy` and the
incoming/outgoing calls for Java documents to faces-ls instead of jdtls.

Code action kinds: `quickfix` (declare an undeclared prefix that is predefined in `namespaces`, add missing
required attributes) and `source.addMissingImports` (declare all such prefixes), which editors can run on save.

## Neovim 0.11+

Built-in LSP configuration, no plugins needed (`init.lua`):

```lua
local faces = vim.fn.expand('~/opt/faces-ls')   -- the unpacked distribution

vim.lsp.config('faces_ls', {
  cmd = { faces .. '/bin/faces-ls' },
  filetypes = { 'xhtml' },
  root_markers = { 'pom.xml', 'build.gradle', 'build.gradle.kts', '.git' },
  init_options = {
    namespaces = { o = 'omnifaces' },
  },
})
vim.lsp.enable('faces_ls')
```

jdtls with the bundle, using the built-in client:

```lua
vim.lsp.config('jdtls', {
  cmd = { 'jdtls', '-data', vim.fn.stdpath('cache') .. '/jdtls-workspace' },
  filetypes = { 'java' },
  root_markers = { 'pom.xml', 'build.gradle', '.git' },
  init_options = { bundles = { faces .. '/lib/faces-jdt.jar' } },
})
vim.lsp.enable('jdtls')
```

With **nvim-jdtls** add the jar to the bundles you already pass:

```lua
require('jdtls').start_or_attach({
  cmd = { 'jdtls' },
  init_options = { bundles = { faces .. '/lib/faces-jdt.jar' } },
})
```

With **nvim-lspconfig**'s `jdtls` configuration, set the same `init_options.bundles`.

To declare missing namespaces on save:

```lua
vim.api.nvim_create_autocmd('BufWritePre', {
  pattern = '*.xhtml',
  callback = function()
    vim.lsp.buf.code_action({ context = { only = { 'source.addMissingImports' } }, apply = true })
  end,
})
```

## Other editors

Any client works the same way: run `bin/faces-ls` for `xhtml` files and add `lib/faces-jdt.jar` to jdtls'
`bundles`. For example Helix (`languages.toml`):

```toml
[language-server.faces-ls]
command = "/opt/faces-ls/bin/faces-ls"

[[language]]
name = "xhtml"
scope = "text.xhtml"
file-types = ["xhtml"]
language-servers = ["faces-ls"]

[language-server.jdtls.config]
bundles = ["/opt/faces-ls/lib/faces-jdt.jar"]
```

The Neovim setup above is tested; the Helix snippet is a starting point.
