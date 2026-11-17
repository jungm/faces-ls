import * as vscode from 'vscode';
import {
  CallHierarchyIncomingCallsRequest,
  CallHierarchyOutgoingCallsRequest,
  CallHierarchyPrepareRequest,
  DefinitionRequest,
  HoverRequest,
  InlayHintRefreshRequest,
  InlayHintRequest,
  LanguageClient,
  ReferencesRequest,
} from 'vscode-languageclient/node';

const JAVA: vscode.DocumentSelector = [
  { scheme: 'file', language: 'java' },
  { scheme: 'jdt', language: 'java' },
];

/** The subset of the Red Hat Java extension's API used here. */
interface JavaExtensionApi {
  serverReady?: () => Promise<boolean>;
  onDidServerModeChange?: vscode.Event<string>;
}

/** Java call hierarchy from faces-ls: jdtls' own hierarchy (through faces-jdt) plus the Facelets files whose EL calls a member. */
class CallHierarchy implements vscode.CallHierarchyProvider {
  constructor(private readonly client: LanguageClient) {}

  async prepareCallHierarchy(
    document: vscode.TextDocument,
    position: vscode.Position,
    token: vscode.CancellationToken,
  ): Promise<vscode.CallHierarchyItem[] | undefined> {
    const params = this.client.code2ProtocolConverter.asTextDocumentPositionParams(document, position);
    const items = await this.client.sendRequest(CallHierarchyPrepareRequest.type, params, token);
    return this.client.protocol2CodeConverter.asCallHierarchyItems(items, token);
  }

  async provideCallHierarchyIncomingCalls(
    item: vscode.CallHierarchyItem,
    token: vscode.CancellationToken,
  ): Promise<vscode.CallHierarchyIncomingCall[] | undefined> {
    const params = { item: this.client.code2ProtocolConverter.asCallHierarchyItem(item) };
    const calls = await this.client.sendRequest(CallHierarchyIncomingCallsRequest.type, params, token);
    return this.client.protocol2CodeConverter.asCallHierarchyIncomingCalls(calls, token);
  }

  async provideCallHierarchyOutgoingCalls(
    item: vscode.CallHierarchyItem,
    token: vscode.CancellationToken,
  ): Promise<vscode.CallHierarchyOutgoingCall[] | undefined> {
    const params = { item: this.client.code2ProtocolConverter.asCallHierarchyItem(item) };
    const calls = await this.client.sendRequest(CallHierarchyOutgoingCallsRequest.type, params, token);
    return this.client.protocol2CodeConverter.asCallHierarchyOutgoingCalls(calls, token);
  }
}

/** The Facelets files whose EL uses the Java member at a position; VS Code adds them to jdtls' references. */
class References implements vscode.ReferenceProvider {
  constructor(private readonly client: LanguageClient) {}

  async provideReferences(
    document: vscode.TextDocument,
    position: vscode.Position,
    context: vscode.ReferenceContext,
    token: vscode.CancellationToken,
  ): Promise<vscode.Location[] | undefined> {
    const params = this.client.code2ProtocolConverter.asReferenceParams(document, position, context);
    const locations = await this.client.sendRequest(ReferencesRequest.type, params, token);
    return this.client.protocol2CodeConverter.asReferences(locations, token);
  }
}

/** What extensions of faces-ls know about a position in Java code, e.g. the definition of a message key; VS Code adds it to jdtls' definitions. */
class Definitions implements vscode.DefinitionProvider {
  constructor(private readonly client: LanguageClient) {}

  async provideDefinition(
    document: vscode.TextDocument,
    position: vscode.Position,
    token: vscode.CancellationToken,
  ): Promise<vscode.Definition | vscode.DefinitionLink[] | undefined> {
    const params = this.client.code2ProtocolConverter.asTextDocumentPositionParams(document, position);
    const result = await this.client.sendRequest(DefinitionRequest.type, params, token);
    return this.client.protocol2CodeConverter.asDefinitionResult(result, token);
  }
}

/** What extensions of faces-ls say about a position in Java code, e.g. the values of a message key; VS Code shows it with jdtls' hover. */
class Hovers implements vscode.HoverProvider {
  constructor(private readonly client: LanguageClient) {}

  async provideHover(
    document: vscode.TextDocument,
    position: vscode.Position,
    token: vscode.CancellationToken,
  ): Promise<vscode.Hover | undefined> {
    const params = this.client.code2ProtocolConverter.asTextDocumentPositionParams(document, position);
    const hover = await this.client.sendRequest(HoverRequest.type, params, token);
    return this.client.protocol2CodeConverter.asHover(hover);
  }
}

/** The inlay hints of extensions of faces-ls in Java code; VS Code adds them to jdtls' hints. */
class InlayHints implements vscode.InlayHintsProvider {
  readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeInlayHints = this.changed.event;

  constructor(private readonly client: LanguageClient) {}

  async provideInlayHints(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[] | undefined> {
    const params = {
      textDocument: this.client.code2ProtocolConverter.asTextDocumentIdentifier(document),
      range: this.client.code2ProtocolConverter.asRange(range),
    };
    const hints = await this.client.sendRequest(InlayHintRequest.type, params, token);
    return this.client.protocol2CodeConverter.asInlayHints(hints, token);
  }
}

/** What faces-ls announces under `experimental.faces` when one of its extensions answers requests on Java documents. */
interface JavaCapabilities {
  javaDefinition?: boolean;
  javaHover?: boolean;
  javaInlayHints?: boolean;
}

/**
 * Facelets usages in the Java editor: references, merged by VS Code with jdtls' own, and the call hierarchy, which
 * faces-ls answers in place of jdtls'. Both are registered while faces-ls reaches faces-jdt, after jdtls is ready.
 * VS Code asks only one call hierarchy provider per document, the most recently registered among equally specific
 * ones, so that provider is registered again when a Java editor becomes active, so that it is the newest one even
 * after jdtls registered its providers anew.
 *
 * When an extension of faces-ls takes Java documents, definitions, hovers and inlay hints are registered alongside.
 */
export class JavaProviders implements vscode.Disposable {
  private readonly disposables: vscode.Disposable[] = [];
  private registration: vscode.Disposable | undefined;
  private references: vscode.Disposable | undefined;
  private definitions: vscode.Disposable | undefined;
  private hovers: vscode.Disposable | undefined;
  private inlayHints: vscode.Disposable | undefined;
  private hints: InlayHints | undefined;
  private connected = false;
  private generation = 0;
  private watchingModes = false;

  constructor(
    private readonly client: LanguageClient,
    private readonly log: vscode.LogOutputChannel | undefined,
  ) {
    this.disposables.push(
      client.onNotification('faces/javaStatus', (status: { connected: boolean }) => {
        if (status.connected !== this.connected) {
          this.connected = status.connected;
          void this.update();
        }
      }),
      vscode.window.onDidChangeActiveTextEditor((editor) => {
        if (editor?.document.languageId === 'java' && this.registration) {
          this.register();
        }
      }),
    );
  }

  private async update(): Promise<void> {
    const generation = ++this.generation;
    this.unregister();
    if (!this.connected) {
      this.unregisterReferences();
      return;
    }
    const api = await javaApi();
    if (api?.serverReady) {
      await api.serverReady();
    }
    if (api?.onDidServerModeChange && !this.watchingModes) {
      this.watchingModes = true;
      this.disposables.push(api.onDidServerModeChange(() => void this.update()));
    }
    if (generation !== this.generation || !this.connected) {
      return;
    }
    this.register();
    this.references ??= vscode.languages.registerReferenceProvider(JAVA, new References(this.client));
    this.log?.info('Java references and call hierarchy include Facelets usages');
    this.registerExtensionProviders();
  }

  private registerExtensionProviders(): void {
    const experimental = this.client.initializeResult?.capabilities.experimental as { faces?: JavaCapabilities } | undefined;
    if (experimental?.faces?.javaDefinition) {
      this.definitions ??= vscode.languages.registerDefinitionProvider(JAVA, new Definitions(this.client));
    }
    if (experimental?.faces?.javaHover) {
      this.hovers ??= vscode.languages.registerHoverProvider(JAVA, new Hovers(this.client));
    }
    if (experimental?.faces?.javaInlayHints && !this.inlayHints) {
      const hints = this.hints ?? this.watchInlayHintRefresh();
      this.inlayHints = vscode.languages.registerInlayHintsProvider(JAVA, hints);
    }
  }

  /**
   * The provider of Java inlay hints, told when faces-ls asks for a refresh. The language client knows only its own
   * provider, the one of Facelets documents, and takes one handler per request, so this one tells both.
   */
  private watchInlayHintRefresh(): InlayHints {
    const hints = new InlayHints(this.client);
    this.hints = hints;
    this.disposables.push(
      hints.changed,
      this.client.onRequest(InlayHintRefreshRequest.type, async () => {
        hints.changed.fire();
        const feature = this.client.getFeature(InlayHintRequest.method);
        const told = new Set<unknown>();
        for (const document of vscode.workspace.textDocuments) {
          const provider = feature.getProvider(document);
          if (provider && !told.has(provider)) {
            told.add(provider);
            provider.onDidChangeInlayHints.fire();
          }
        }
      }),
    );
    return hints;
  }

  private register(): void {
    this.unregister();
    this.registration = vscode.languages.registerCallHierarchyProvider(JAVA, new CallHierarchy(this.client));
  }

  private unregister(): void {
    this.registration?.dispose();
    this.registration = undefined;
  }

  private unregisterReferences(): void {
    this.references?.dispose();
    this.references = undefined;
    this.definitions?.dispose();
    this.definitions = undefined;
    this.hovers?.dispose();
    this.hovers = undefined;
    this.inlayHints?.dispose();
    this.inlayHints = undefined;
  }

  dispose(): void {
    this.generation++;
    this.unregister();
    this.unregisterReferences();
    this.disposables.forEach((d) => d.dispose());
  }
}

async function javaApi(): Promise<JavaExtensionApi | undefined> {
  const extension = vscode.extensions.getExtension<JavaExtensionApi>('redhat.java');
  if (!extension) {
    return undefined;
  }
  try {
    return extension.isActive ? extension.exports : await extension.activate();
  } catch {
    return undefined;
  }
}
