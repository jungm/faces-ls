-- Minimal Neovim 0.11 setup for faces-ls without plugins, used by scripts/test-nvim.sh.
-- FACES_DIST: unpacked distribution; JDTLS: jdtls launcher; WORK: scratch directory.
local dist = vim.env.FACES_DIST
local work = vim.env.WORK

vim.lsp.config('jdtls', {
  -- keep jdtls from writing .project/.classpath/.settings into the sample
  cmd = { vim.env.JDTLS, '--jvm-arg=-Djava.import.generatesMetadataFilesAtProjectRoot=false', '-data', work .. '/jdtls-data' },
  filetypes = { 'java' },
  root_markers = { 'pom.xml', 'build.gradle', '.git' },
  init_options = { bundles = { dist .. '/lib/faces-jdt.jar' } },
})

vim.lsp.config('faces_ls', {
  cmd = { dist .. '/bin/faces-ls' },
  filetypes = { 'xhtml' },
  root_markers = { 'pom.xml', 'build.gradle', '.git' },
  init_options = { log_file = work .. '/faces-ls.log' },
})

vim.lsp.enable({ 'jdtls', 'faces_ls' })
