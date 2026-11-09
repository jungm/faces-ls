import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind } from 'vscode-languageclient/node';

const MIN_JAVA_VERSION = 21;

let client: LanguageClient | undefined;
let output: vscode.LogOutputChannel | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  output = vscode.window.createOutputChannel('Faces Language Server', { log: true });
  context.subscriptions.push(output);

  context.subscriptions.push(
    vscode.commands.registerCommand('faces.restartServer', async () => {
      await stopClient();
      await startClient(context);
    }),
    vscode.commands.registerCommand('faces.showOutput', () => output?.show()),
    vscode.workspace.onDidChangeConfiguration(async (event) => {
      if (event.affectsConfiguration('faces.java.home') || event.affectsConfiguration('faces.server')) {
        const restart = 'Restart';
        const choice = await vscode.window.showInformationMessage(
          'The Faces language server needs a restart to apply the changed settings.',
          restart,
        );
        if (choice === restart) {
          await vscode.commands.executeCommand('faces.restartServer');
        }
      }
    }),
  );

  await startClient(context);
}

export function deactivate(): Thenable<void> | undefined {
  return stopClient();
}

async function startClient(context: vscode.ExtensionContext): Promise<void> {
  const config = vscode.workspace.getConfiguration('faces');
  const serverDir = context.asAbsolutePath('server');
  const serverJar = config.get<string | null>('server.jar') || path.join(serverDir, 'faces-ls.jar');
  if (!fs.existsSync(serverJar)) {
    void vscode.window.showErrorMessage(`Faces: language server not found at ${serverJar}.`);
    return;
  }

  const java = findJava(config.get<string | null>('java.home'));
  if (!java) {
    void vscode.window.showErrorMessage(
      `Faces: no Java ${MIN_JAVA_VERSION}+ runtime found. Set "faces.java.home" or JAVA_HOME, or install the Red Hat Java extension.`,
    );
    return;
  }
  output?.info(`Starting ${serverJar} with ${java}`);

  const vmargs = config.get<string[]>('server.vmargs') ?? [];
  const serverOptions: ServerOptions = {
    command: java,
    args: [...vmargs, '-jar', serverJar],
    transport: TransportKind.stdio,
    options: {
      env: {
        ...process.env,
        // The Red Hat Java extension loads server/faces-jdt.jar into jdtls (contributes.javaExtensions); the bundle
        // announces its socket in this directory next to the jar.
        FACES_JDT_RENDEZVOUS: path.join(serverDir, 'faces-jdt-rendezvous'),
      },
    },
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: 'file', language: 'facelets' }],
    outputChannel: output,
    initializationOptions: { settings: serverSettings(config) },
    synchronize: {
      configurationSection: 'faces',
    },
  };

  client = new LanguageClient('faces', 'Faces Language Server', serverOptions, clientOptions);
  try {
    await client.start();
  } catch (error) {
    client = undefined;
    output?.error('Language server failed to start', error);
    void vscode.window.showErrorMessage(`Faces: the language server failed to start: ${error}`);
  }
}

async function stopClient(): Promise<void> {
  const running = client;
  client = undefined;
  if (running) {
    await running.stop();
  }
}

/** The settings object faces-ls reads, in its own key names. */
function serverSettings(config: vscode.WorkspaceConfiguration): Record<string, unknown> {
  const settings: Record<string, unknown> = {
    namespaces: config.get('namespaces') ?? {},
  };
  const logFile = config.get<string | null>('server.logFile');
  if (logFile) {
    settings.log_file = logFile;
  }
  return settings;
}

/**
 * The java executable of the first runtime with at least {@link MIN_JAVA_VERSION}: the configured one, JAVA_HOME,
 * the runtime bundled with the Red Hat Java extension, then `java` on PATH (unchecked).
 */
function findJava(configuredHome: string | null | undefined): string | undefined {
  const homes: string[] = [];
  if (configuredHome) {
    homes.push(configuredHome);
  }
  if (process.env.JAVA_HOME) {
    homes.push(process.env.JAVA_HOME);
  }
  homes.push(...redHatJavaRuntimes());

  for (const home of homes) {
    const executable = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    const version = javaVersion(home);
    if (fs.existsSync(executable) && (version === undefined || version >= MIN_JAVA_VERSION)) {
      return executable;
    }
    output?.info(`Skipping ${home}: Java ${version ?? 'unknown'}, need ${MIN_JAVA_VERSION}+`);
  }
  return onPath(process.platform === 'win32' ? 'java.exe' : 'java');
}

/** Java homes under the `jre` directory of the Red Hat Java extension. */
function redHatJavaRuntimes(): string[] {
  const extension = vscode.extensions.getExtension('redhat.java');
  if (!extension) {
    return [];
  }
  const jreDir = path.join(extension.extensionPath, 'jre');
  try {
    return fs
      .readdirSync(jreDir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => path.join(jreDir, entry.name));
  } catch {
    return [];
  }
}

/** The feature version from `<home>/release` (`JAVA_VERSION="21.0.2"` gives 21), if the file exists. */
function javaVersion(home: string): number | undefined {
  try {
    const release = fs.readFileSync(path.join(home, 'release'), 'utf8');
    const match = /^JAVA_VERSION="(\d+)(?:\.(\d+))?/m.exec(release);
    if (!match) {
      return undefined;
    }
    // Java 8 and older report 1.x
    return match[1] === '1' && match[2] ? Number(match[2]) : Number(match[1]);
  } catch {
    return undefined;
  }
}

function onPath(executable: string): string | undefined {
  for (const dir of (process.env.PATH ?? '').split(path.delimiter)) {
    const candidate = path.join(dir, executable);
    if (dir && fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return undefined;
}
