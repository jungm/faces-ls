import * as vscode from 'vscode';
import {
  CallHierarchyIncomingCallsRequest,
  CallHierarchyOutgoingCallsRequest,
  CallHierarchyPrepareRequest,
  LanguageClient,
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
class Provider implements vscode.CallHierarchyProvider {
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

/**
 * Puts faces-ls' Java call hierarchy in place of jdtls'. VS Code asks only one call hierarchy provider per document,
 * the most recently registered among equally specific ones, so the provider is registered while faces-ls reaches
 * faces-jdt (otherwise it could not answer at all), after jdtls is ready, and again whenever jdtls registers its
 * providers anew (switch from syntax to standard mode).
 */
export class JavaCallHierarchy implements vscode.Disposable {
  private readonly disposables: vscode.Disposable[] = [];
  private registration: vscode.Disposable | undefined;
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
    );
  }

  private async update(): Promise<void> {
    const generation = ++this.generation;
    this.unregister();
    if (!this.connected) {
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
    this.registration = vscode.languages.registerCallHierarchyProvider(JAVA, new Provider(this.client));
    this.log?.info('Java call hierarchy includes Facelets callers');
  }

  private unregister(): void {
    this.registration?.dispose();
    this.registration = undefined;
  }

  dispose(): void {
    this.generation++;
    this.unregister();
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
