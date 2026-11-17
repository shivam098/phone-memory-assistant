// Desktop only. No account, personal data, model conversion or third-party packages.
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, readFile, rename, rm, stat } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const catalog = JSON.parse(await readFile(new URL('../app/src/main/assets/models.json', import.meta.url)));
const [kind, output = fileURLToPath(new URL('../local-models', import.meta.url))] = process.argv.slice(2);
if (!['speech', 'text'].includes(kind)) throw Error('Usage: node tools/fetch-models.mjs speech|text [output-folder]');
const model = catalog[kind];
const dir = resolve(output, kind);
await mkdir(dir, { recursive: true });
for (const { name, size, sha256 } of model.files) {
  const dest = join(dir, name);
  async function valid(path) {
    if ((await stat(path)).size !== size) return false;
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(path)) hash.update(chunk);
    return hash.digest('hex') === sha256;
  }
  if (await valid(dest).catch(e => { if (e.code === 'ENOENT') return false; throw e; })) {
    console.log(`Already verified: ${name}`);
    continue;
  }
  const tmp = join(dir, `${randomUUID()}.part`);
  try {
    const url = `https://huggingface.co/${model.repo}/resolve/${model.revision}/${name}`;
    const response = await fetch(url);
    if (!response.ok || !response.body) throw Error(`Download failed: HTTP ${response.status}`);
    let bytes = 0;
    const limit = new Transform({ transform(chunk, _, cb) {
      bytes += chunk.length;
      cb(bytes > size ? Error('Unexpected model size') : null, chunk);
    } });
    await pipeline(Readable.fromWeb(response.body), limit, createWriteStream(tmp, { flags: 'wx' }));
    if (!(await valid(tmp))) throw Error(`Size or SHA-256 mismatch: ${name}`);
    await rename(tmp, dest);
    console.log(`Verified: ${name}`);
  } finally { await rm(tmp, { force: true }); }
}
