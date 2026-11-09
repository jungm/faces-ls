package io.github.jungm.fls;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Starts faces-ls on stdin/stdout.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        OutputStream out = System.out;
        // anything printed by libraries must not corrupt the protocol stream
        System.setOut(new PrintStream(System.err, true));
        FacesLanguageServer server = new FacesLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
        // the client closed the connection (possibly without shutdown/exit): lsp4j's worker threads would keep the
        // JVM alive
        System.exit(0);
    }
}
