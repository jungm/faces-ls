# Changelog

## Unreleased

## 1.3.0 (2026-11-16)

- **Completion for Faces resources**: `library` and `name` of `h:graphicImage`, `h:outputScript`,
  `h:outputStylesheet`, `#{resource['library:name']}` and `f:view contracts` offer the
  libraries and names of the web app, other modules, jars and resource library contracts. `outputScript` only offers
  scripts and `outputStylesheet` only stylesheets.
- **Resource previews on hover**: hovering a resource shows the image itself, the first lines of stylesheets, scripts
  and other text files, or the size and origin (including the jar) of everything else.
- **EL in files other than Facelets pages**: completion, hover, go to definition and diagnostics for `#{...}` in
  stylesheets served as Faces resources, and in further files matched by the new `faces.elFiles` setting, e.g.
  properties files. `#{...}` is highlighted in CSS and properties files.
- **Faster Java answers**: requests to the Java side no longer wait some 80 ms for a delayed TCP acknowledgement, so
  a repeated query takes about 8 ms instead of 100 ms.
- **Extensions of the language server**: support for frameworks on top of Faces can come as jars that plug into the
  language server, listed in the new `faces.server.extensions` setting or contributed by other VS Code extensions
  through `contributes.facesExtensions`. Extensions can add go to definition, hover, inlay hints and commands, also
  in Java files.

## 1.2.0 (2026-11-16)

- **Go to definition on Faces resources**: `library` and `name` of resource tags, `#{resource['library:name']}` and
  `f:view contracts` jump to the file in the web app, other modules, jars or resource library contracts.
- **Search expressions across files**: `update`, `process`, `render`, `execute` and `for` resolve `@form`,
  `@namingcontainer` and plain ids in every page the current file ends up in, so `update="@form:pnl"` in an include
  or composite component finds the form declared in the template.
- A module's own version of a composite component is now preferred over the one in a jar when navigating its pages.

## 0.1.2 (2026-11-12)

- Keywords for the Marketplace and links to the Marketplace and Open VSX listings.
- The extension is now released automatically to both the VS Code Marketplace and Open VSX.

## 0.1.1 (2026-11-12)

- Extension icon and a shorter README.

## 0.1.0 (2026-11-12)

First release of Jakarta Faces support for VS Code, powered by the Faces Language Server.

- Syntax highlighting, snippets and a `facelets` language for `*.xhtml` pages.
- Completion, hover and go to definition for tags, attributes and EL: beans, properties, methods, resource bundle
  keys and taglib functions.
- Tags from the tag libraries and composite components of the project and its jars, so PrimeFaces, OmniFaces and
  custom components need no setup.
- Navigation through templates, includes and composite components, also into other modules and jars; go to
  definition on `ui:define` jumps to every matching `ui:insert` up the template chain.
- EL knows the classes imported by `f:importConstants`, `p:importEnum`, `p:importConstants`, `o:importConstants` and
  `o:importFunctions`; the `faces.imports` setting registers further tags.
- Passthrough elements: plain HTML elements with `jsf:` attributes are treated as the Faces tag they become.
- Diagnostics for unknown tags and attributes, missing required attributes, EL errors and unresolved names; quick
  fixes to declare namespaces and add required attributes.
- Together with Language Support for Java by Red Hat: find references and the call hierarchy of Java members include
  the Facelets pages that use them.
- Settings `faces.java.home`, `faces.server.jar`, `faces.server.vmargs`, `faces.server.logFile`, `faces.namespaces`
  and `faces.trace.server`; commands to restart the language server and show its output.
