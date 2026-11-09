# IntelliJ Faces/EL support: feature inventory

Source: IntelliJ IDEA 2026.3 (build 263.7307). This list comes from the descriptors, message bundles, inspection
descriptions, bundled schemas and class names of these plugins:

- `com.intellij.jsf`, "Jakarta EE: Server Faces (JSF)" (`javaee-jsf.jar` plus the `cdi` and `appServers` modules)
- `com.intellij.javaee.el`, "Jakarta EE: Expression Language (EL)" (`javaee-el-core.jar`)
- `com.intellij.jsp`: the EL completion, resolving, refactoring and inspection code lives here and is shared with JSF
- `com.intellij.cdi`: supplies `@Named` beans to EL through `CdiContextBeansProvider`

How each feature can be built with faces-ls and an editor extension:

- **GR**: the editor extension's TextMate grammar and language configuration
- **SN**: snippets
- **LSP**: faces-ls, which knows the project's Java classpath
- **X**: needs editor UI that LSP does not cover

## 1. Facelets language

| Feature | IntelliJ implementation | Approach |
|---|---|---|
| `Facelets` language/file type. `.xhtml`/`.jspx` files switch to it when Facelets namespaces are present | `FaceletsLanguageProvider` (`lang.substitutor`), `FaceletsFileType`, `FaceletsFileViewProvider` | GR (a Facelets language for `*.xhtml` on an XML/HTML grammar) |
| Facelets-aware editor highlighting (XML plus embedded EL) | `FaceletsEditorHighlighterProvider` | GR |
| Each tag-library prefix gets its own colour (`h:`, `f:`, `ui:` …) | `FaceletsNSColorProvider` | LSP (semantic tokens mark tag prefixes) |
| Emmet / zen coding inside Facelets | `FaceletsZenCodingGenerator` | X (editors run Emmet for HTML languages only) |
| Composite component tags formatted as inline tags | `CompositeComponentsInlineTagContributor` | LSP (formatting), or skip |
| Structure view for faces XML | `JsfXmlStructureViewElementProvider` | LSP (`documentSymbol`) or GR |

## 2. Expression Language (EL) as a language

| Feature | IntelliJ implementation | Approach |
|---|---|---|
| EL lexer and parser: `${…}` (immediate) and `#{…}` (deferred); operators, literals, lambdas, list/map literals (`ELLambdaParameters`, `ELDataList`, `ELMapEntryList`), method calls with parameters | `ELLexer`, `ELParserDefinition`, `psi/*` | GR (EL grammar) for highlighting; faces-ls has its own EL parser |
| Syntax highlighting of EL embedded in XML attributes and text | `ELHighlighter` (also an `embeddedTokenHighlighter`) | GR (EL injected into attribute values and text) |
| Brace matching for `{}`, `()`, `[]` | `ELBraceMatcher` | GR |
| Typing assistance (auto-close `#{`) | `ELTypedHandler` | GR (auto-closing pairs) |
| Extend-selection by EL structure | `ELExpressionHolderSelectioner` | LSP (`selectionRange`) |
| Parameter info for EL method and function calls | `ELParameterInfoHandler` | LSP (`signatureHelp`) |

## 3. EL semantics: resolution, completion, navigation

All of this needs a model of the Java classpath, so it is all **LSP**.

| Feature | IntelliJ implementation |
|---|---|
| Completion and go-to-definition for bean names: `@Named` CDI beans, `@ManagedBean`, `<managed-bean>` in faces-config | `CdiContextBeansProvider`, `JsfJamManagedBeansContextBeansProvider`, `FacesConfigsContributor` |
| Completion and navigation for properties (getter/setter/`is`), method calls and method expressions (`action="#{bean.save}"`). Resolves through the Java type chain: `a.b.c`, `[]` indexing, Map/List/array element types, generics | `ELReference`, `ELResolveUtil`, `PsiTypeELElementProcessor`, `ElComponentTypeUtil` |
| Implicit objects: `facesContext`, `view`, `component`, `cc`, `request`, `session`, `application`, `param`, `paramValues`, `header`, `headerValues`, `cookie`, `initParam`, `requestScope`, `viewScope`, `sessionScope`, `applicationScope`, `flowScope`, `flash`, `resource`, … | `JsfElVariablesProvider`, `PredefinedVariablesContributor`, `JspElVariablesProvider` |
| Variables declared by tags: `var` on `h:dataTable`, `ui:repeat`, `c:forEach`; `ui:param`; `f:loadBundle`; `<resource-bundle>` vars from faces-config; `c:set` | `JsfElVariablesContributor`, `UIParamVariablesContributor`, `ResourceBundleContributor`, `JsfLightVariableBuilder`, `TagImplicitVariable` |
| Resource bundle keys: `#{msg['some.key']}` / `#{msg.key}` resolve to `.properties` entries | `ResourceBundleContributor` |
| Constants imported with `<f:importConstants>` (`#{Consts.FOO}`) | `ImportedConstantsVariablesContributor` |
| Composite components: `#{cc.attrs.x}` resolves to `<cc:attribute name="x">`; also `cc.clientId`, `cc.parent` | `CompositeImplementationsELTagContributor`, `CompositeInterfaceAttributeImplicitVariable`, `CompositeComponentImplicitVariable` |
| Faces Flows: `#{flowScope…}`, flow names and node IDs from `@FlowDefinition` / `@FlowScoped` | `AnnotatedFlowsELInjector`, `flows/*` |
| Custom `ELResolver`s registered in faces-config are taken into account | `CustomVariableResolversContributor`, `JsfElResolverReferenceContributor`, `ELResolverManagedBeanReferenceProvider` |
| EL functions with a namespace prefix (`fn:length(…)`, taglib `<function>`) | `FunctionELReference`, `NamespaceELReference` |
| `#{resource['lib:file.css']}`: the string literal resolves to a file under `resources/` | `ElResourceReferenceProvider` |
| EL inside Java: `@ManagedProperty("#{…}")`, `@FlowDefinition`, `ValueExpression` strings | `AnnotatedManagedBeanELClassInjector` |
| EL inside faces-config XML values | `FacesConfigElProvider` |
| Find usages of a Java property or method from EL, and from Java into `.xhtml` | `ELFindUsagesProvider` |
| Rename: renaming a Java property or method or a bean name updates EL; renaming in EL renames Java (getter/setter pairs too) | `ELPropertyRenameHandler`, `ELVariableRenameProcessor`, `ELRefactoringSupportProvider` |
| Getters/setters/beans used only from EL are not reported as unused in Java | `JsfImplicitUsageProvider`, `JsfManagedBeansPropertyScopeProvider` |
| Quick fixes for unresolved references (create property/method; declare variable via comment annotation) | `ElReferenceQuickFixProvider`, `DeclareELVarViaCommentAnnotationFix` |

The Java → `.xhtml` direction (find usages and rename starting from Java) only works if the Faces server also gets
`textDocument/references` and `rename` for Java files. Two ways: register it for the Java language too, next to jdtls,
or plug in as a jdtls extension bundle.

## 4. EL inspections (diagnostics, all LSP)

| Inspection | Reports |
|---|---|
| `ELValidationInspection` | Cannot resolve variable / property or method / method / function / namespace; different number of formal and actual parameters; invalid EL location |
| `ELMethodSignatureInspection` | A method expression that doesn't match the `method-signature` required by the tag attribute (e.g. `action` must be `Object x()`, `actionListener` takes `ActionEvent`) |
| `ELDeferredExpressionsInspection` | `${}` where `#{}` is required and vice versa (e.g. `id` must be immediate, `value` deferred) |
| `ELSpecValidationInJSP` | Non-standard EL extensions, EL outside attributes where it isn't allowed |
| `FunctionELReferenceInspection` | Wrong parameter count in EL function calls |
| `JspUnescapedEl` | XSS: tainted data rendered unescaped. A Facelets equivalent is `h:outputText escape="false"` |

## 5. Tags and attributes (Facelets markup)

| Feature | IntelliJ implementation | Approach |
|---|---|---|
| Tag and attribute completion, docs and validation from bundled taglibs for each spec version (EE6/7/8, Jakarta 10/11): `h:`, `f:`, `ui:`, `cc:` (composite), `c:` (JSTL core), `fn:`, `pt:` (passthrough attributes), `jsf:` (passthrough elements), mojarra extensions | `JsfSchemaProvider_*`, `FaceletsSchemaProvider_*`, bundled `schemas/**/*.taglib.xml`, `*.tld` | LSP. lemminx could cover part of it with XSD, but taglib.xml files aren't XSDs, so a custom server is the better fit |
| Both namespace families: `http://xmlns.jcp.org/jsf/*`, `http://java.sun.com/jsf/*`, `jakarta.faces.*` URNs | `JsfNamespaceConstants` | LSP |
| Third-party / project taglibs: `*.taglib.xml` on the classpath and in `WEB-INF`, `jakarta.faces.FACELETS_LIBRARIES` context param | `FaceletsTaglibProcessorFactory`, `FaceletLabrariesPathListProvider` | LSP |
| Typed attribute values: enum variants (`dir="LTR,RTL"`, `type="submit,button,reset"`), boolean, integer, path, JavaScript event attributes. Declared per tag in XML files for core JSF and for PrimeFaces, RichFaces, ICEfaces, OpenFaces | `references/libraries/extensions/**.xml`, `JsfComponentLibraryReferenceProvider` | LSP (the data format can be ported) |
| JavaScript injected into `on*` attributes and `h:outputScript` bodies; CSS into `style`/`styleClass` and `h:outputStylesheet` | `JsfJSInjector`, `JsfCssLanguageInjector`, `CssOutputStyleSheetInjector`, `injections/xmlInjections.xml` | GR (embedded languages in the grammar) |
| HTML5-friendly markup: `jsf:` passthrough elements (`<input jsf:value="…">`), `pt:`/passthrough attribute completion, `jsf:id` IDs | `html5/*`, `PassThroughAttrCompletionContributor`, `JsfHtmlDecorator`, `TagDecoratorManager` | LSP |
| Component ID references: `for`, `render`, `execute`, `update` (incl. `@form`, `@this` …) resolve to `id`s in the page | `JsfImplicitIdRefAttrProvider`, `PassThroughIdPsiReferenceProvider`, `AbstractForAttributeReferenceProvider` | LSP |
| Converter and validator IDs (`converter="…"`, `f:converter converterId`, `f:validator validatorId`): completion and navigation to `@FacesConverter`/`@FacesValidator` or faces-config entries; predefined converters | `JsfConverterIdXmlAttributeReferencesProvider`, `JsfConvertersDiscoverer` (DOM/JAM/predefined) | LSP |
| Navigation outcomes in `action="page"` / `outcome="page"` resolve to view files | `FacesActionReferenceProvider`, `ActionAttributeValueReference` | LSP |
| View/file paths: `ui:include src`, `ui:composition template`, `ui:decorate template`, `h:link outcome`, `<from-view-id>`/`<to-view-id>` | `FacesWebPathProvider`, `FacesPathReferenceConverter`, `AsteriskPathReferenceConverter` | LSP (editors' own file links don't resolve web-root paths) |
| `ui:insert name` ↔ `ui:define name` across template and client | `FaceletsUiDefineReferenceProvider` | LSP |
| Resources: `h:outputScript`/`h:outputStylesheet`/`h:graphicImage` `library`+`name` resolve to `resources/<library>/<name>`; resource library contracts (`contracts=`); `@ResourceDependency` in Java | `JsfResourceIdentifierAttributeReferencesProvider`, `JsfLibraryDirectoryReference`, `JsfContractsPathReferenceProvider`, `JsfJamResourceDependency` | LSP |
| Quick fix: create a missing resource directory | `JsfResourceDirectoryReference` | LSP (code action) |
| Value-format validation ("Cannot convert string to target class", "Valid values are …") | `FacesBundle: value.format.exception*` | LSP |
| Error-page paths in web.xml (default error page) | `JsfDefaultErrorPagePathReferenceProvider` | LSP |
| `web.xml` `<context-param>` names for Faces (`jakarta.faces.PROJECT_STAGE`, …) | `JsfContextParamsProvider` | LSP (lemminx could also do this) |

## 6. Composite components

| Feature | IntelliJ implementation | Approach |
|---|---|---|
| Namespace `http://xmlns.jcp.org/jsf/composite/<dir>` (and the Jakarta equivalent) maps to components found in `resources/<dir>/*.xhtml`; tag completion from the files | `CompositeComponentNamespaceDescriptorProvider`, `CompositeNamespaceDescriptor`, `CompositeURIReferenceProvider` | LSP |
| Component attributes come from `<cc:interface>` / `<cc:attribute>`: completion, validation, required attributes | `CompositeComponentDescriptor`, `CompositeComponentAttributeDescriptor` | LSP |
| `cc:actionSource` / `cc:valueHolder` / `cc:editableValueHolder` `targets`/`for` resolve to inner IDs; `componentType` resolves to a Java class | `CompositeActionSourceForAttributeReferenceProvider`, `CompositeValueHolder*`, `CompositeComponentTypeReferenceProvider` | LSP |
| Quick fixes: create a composite component file for an unknown tag; create a missing `cc:attribute` | `CompositeComponentQuickFixProvider`, `CompositeNamespaceUndefinedElementFixProvider` | LSP (code actions) |
| Refactorings: Extract Composite Component, Introduce Composite Component Parameter | `ExtractFaceletsComponentHandler`, `FaceletsRefactoringSupportProvider` | LSP (code actions with workspace edits) |

## 7. faces-config.xml

| Feature | IntelliJ implementation | Approach |
|---|---|---|
| Schemas for every version, 1.0 … 4.1 | `schemas/web-facesconfig_*.xsd` | lemminx (already supports XSD via `xsi:schemaLocation`), plus an LSP for semantics |
| References: class names (managed-bean-class, converter-class, validator-class, component-class, renderer-class, phase-listener, el-resolver …), managed-property names resolve to bean setters, navigation-rule view IDs, resource-bundle base names | DOM model `model/xml/**`, `ManagedPropertyNameConverter` | LSP |
| `FacesModelInspection`: classes that can't be instantiated, wrong supertype, inappropriate scope, empty values, values that don't match a pattern (Java identifiers) | `JsfDomModelInspection` | LSP |
| `JsfManagedBeansInconsistencyInspection`: managed-property type mismatches | | LSP |
| Duplicate-value warnings | `warning.values.are.duplicated` | LSP |
| Graphical navigation-rule editor (a "Navigation" tab, drag and drop of pages) | `yfilesGraph/*` | X |
| Mark any XML file as a faces config ("Use as Faces Config") | `UserDefinedFacesConfigAction` | X (could be a setting the LSP reads) |

## 8. Java side

| Feature | IntelliJ implementation | Approach |
|---|---|---|
| `JsfJamExtendsClassInconsistencyInspection`: `@FacesComponent` must extend `UIComponent`, `@FacesRenderer` → `Renderer`, `@FacesConverter` → `Converter`, `@FacesValidator` → `Validator` | `highlighting/jam/*` | LSP on Java files (alongside jdtls) |
| `@ResourceDependency(library, name)` values resolve to resources | `JsfJamResourceDependency` | LSP |
| `@ManagedProperty("#{…}")`: EL inside Java strings | `AnnotatedManagedBeanELClassInjector` | GR injection into Java strings for highlighting; LSP for semantics |
| `@FlowDefinition` / flow node references in Java, rename-validated | `FlowReferenceContributor`, `FlowElementRenameValidator` | LSP |
| `@NamedEvent` support | `JsfJamNamedEvent` | LSP |

## 9. Templates and project tooling

| Feature | IntelliJ implementation | Approach |
|---|---|---|
| File templates: Facelets page (JSF 2/Jakarta 8/Jakarta 10), composite component, faces-config 1.0 … 4.1 | `fileTemplates/j2ee/*.ft` | SN (a snippet for the page skeleton) |
| Live templates for Facelets (`faca` = composite attribute …) and faces-config (`propertybean`, `mapentriesbean`, `listentriesbean`, `jsfvalidator` …) | `liveTemplates/*.xml` | SN |
| CRUD generation templates (managed bean/converter, create/edit/view/list pages) | `FacesGenerationTemplatesFactory` | X / out of scope |
| JSF facet, library type, framework detection, Maven dependency support (Mojarra, Jakarta Faces, PrimeFaces, RichFaces, OmniFaces, ICEfaces) | `JsfCoreFrameworkType`, `JsfLibraryType`, `dependencySupport` | X (the LSP can detect these from the classpath) |
| App-server integration (deploying Faces support) | `intellij.javaee.jsf.appServers` | X |
| File icons | `FacesIconProvider` | Icon theme (optional) |
| Compiler-time validation of all Faces files | `FacesValidator` | LSP (workspace diagnostics) |
