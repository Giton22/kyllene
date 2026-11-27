import { readFile, writeFile } from 'node:fs/promises'
import { gzipSync } from 'node:zlib'
import { build } from 'esbuild'

const resource = '../src/jvmMain/resources/agent-screen'
const { metafile, outputFiles } = await build({
  entryPoints: ['screen.js'],
  bundle: true,
  minify: true,
  legalComments: 'external',
  metafile: true,
  write: false,
  outfile: resource + '/renderer.js',
})

for (const file of outputFiles) {
  if (file.path.endsWith('.js')) await writeFile(file.path + '.gz', gzipSync(file.contents, { level: 9 }))
  else await writeFile(file.path, file.contents)
}

const packages = new Set()
for (const { inputs } of Object.values(metafile.outputs)) {
  for (const [path, data] of Object.entries(inputs)) {
    const packageName = /^node_modules\/((?:@[^/]+\/)?[^/]+)\//.exec(path)?.[1]
    if (packageName && data.bytesInOutput > 0) packages.add(packageName)
  }
}
const licenses = await Promise.all([...packages].sort().map(async name => {
  const license = await readFile('node_modules/' + name + '/LICENSE', 'utf8')
  return name + '\n\n' + license.trim()
}))
await writeFile(resource + '/licenses.txt', licenses.join('\n\n---\n\n') + '\n')
