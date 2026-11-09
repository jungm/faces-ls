// Copies faces-ls.jar and faces-jdt.jar into server/, from the language server built with "mvn package" in the
// repository root or from a faces-ls-<version>.zip given as argument.
import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const target = path.join(root, 'server');
const zip = process.argv[2];

let serverJar;
let bundleJar;
if (zip) {
  const extracted = fs.mkdtempSync(path.join(os.tmpdir(), 'faces-ls-'));
  execFileSync('unzip', ['-q', '-o', path.resolve(zip), 'lib/*', '-d', extracted]);
  serverJar = path.join(extracted, 'lib', 'faces-ls.jar');
  bundleJar = path.join(extracted, 'lib', 'faces-jdt.jar');
} else {
  const repoRoot = path.join(root, '..');
  serverJar = path.join(repoRoot, 'faces-ls', 'target', 'faces-ls.jar');
  bundleJar = path.join(repoRoot, 'faces-jdt', 'target', 'faces-jdt.jar');
}

for (const jar of [serverJar, bundleJar]) {
  if (!fs.existsSync(jar)) {
    console.error(`${jar} not found; run "mvn package" in the repository root or pass a faces-ls zip`);
    process.exit(1);
  }
}

fs.mkdirSync(target, { recursive: true });
fs.copyFileSync(serverJar, path.join(target, 'faces-ls.jar'));
fs.copyFileSync(bundleJar, path.join(target, 'faces-jdt.jar'));
console.log(`Copied faces-ls.jar and faces-jdt.jar into ${target}`);
