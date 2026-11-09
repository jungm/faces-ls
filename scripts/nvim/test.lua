-- Drives a headless Neovim through faces-ls requests and writes the results to $OUT
local out = {}
local function log(...) table.insert(out, table.concat(vim.tbl_map(tostring, { ... }), ' ')) end
local root = vim.env.SAMPLE
local page = root .. '/src/main/webapp/order.xhtml'

vim.cmd('edit ' .. root .. '/src/main/java/org/example/web/OrderBean.java')
vim.cmd('edit ' .. page)
local buf = vim.api.nvim_get_current_buf()
log('filetype', vim.bo[buf].filetype)

-- wait until faces-ls has connected to faces-jdt inside jdtls
local connected = vim.wait(120000, function()
  local f = io.open(vim.env.LOG, 'r')
  if not f then return false end
  local text = f:read('*a'); f:close()
  return text:find('has imported') ~= nil or text:find('connected to faces%-jdt in jdtls pid %d+\n') ~= nil
end, 1000)
log('connected', connected)

local function request(method, line, col)
  local params = { textDocument = { uri = vim.uri_from_bufnr(buf) }, position = { line = line, character = col } }
  local results = vim.lsp.buf_request_sync(buf, method, params, 15000) or {}
  for _, r in pairs(results) do if r.result then return r.result end end
end

local lines = vim.api.nvim_buf_get_lines(buf, 0, -1, false)
local function col(line, needle, delta) return (lines[line + 1]:find(needle, 1, true) - 1) + (delta or 0) end

-- diagnostics: wait for the ones that need Java type information (beans load in the background)
local settled = vim.wait(60000, function()
  for _, d in ipairs(vim.diagnostic.get(buf)) do
    if d.message:find('MoneyFormatter', 1, true) then return true end
  end
  return false
end, 500)
log('java diagnostics', settled)
for _, d in ipairs(vim.diagnostic.get(buf)) do log('diagnostic', d.lnum .. ':' .. d.col, d.message) end

-- completion of bean members
local completion = request('textDocument/completion', 14, col(14, '#{money.', 8))
local labels = {}
for _, item in ipairs(completion and (completion.items or completion) or {}) do table.insert(labels, item.label) end
log('completion money.', table.concat(labels, ', '))

-- go to definition: bean, composite tag, PrimeFaces tag
for _, case in ipairs({ { 'money bean', 14, col(14, '#{money.', 3) }, { 's:price tag', 12, col(12, 's:price', 3) },
                        { 'p:inputText tag', 11, col(11, 'p:inputText', 3) } }) do
  local result = request('textDocument/definition', case[2], case[3])
  local location = result and (result[1] or result)
  log('definition ' .. case[1], location and (location.uri or location.targetUri):gsub('.*/', '') .. ':' .. location.range.start.line or 'nothing')
end

-- hover
local hover = request('textDocument/hover', 14, col(14, 'defaultCurrency', 2))
log('hover', hover and hover.contents.value:gsub('\n', ' | ') or 'nothing')

-- semantic tokens of the bean line, decoded with the server's legend
local client = vim.lsp.get_clients({ bufnr = buf, name = 'faces_ls' })[1]
local legend = client.server_capabilities.semanticTokensProvider.legend
local tokens = vim.lsp.buf_request_sync(buf, 'textDocument/semanticTokens/full',
  { textDocument = { uri = vim.uri_from_bufnr(buf) } }, 15000)
local data = {}
for _, r in pairs(tokens or {}) do if r.result then data = r.result.data end end
local decoded, line, character = {}, 0, 0
for i = 1, #data, 5 do
  line = line + data[i]
  character = data[i] == 0 and character + data[i + 1] or data[i + 1]
  if line == 14 then
    local modifiers = {}
    for b, name in ipairs(legend.tokenModifiers) do
      if bit.band(data[i + 4], bit.lshift(1, b - 1)) ~= 0 then table.insert(modifiers, name) end
    end
    table.insert(decoded, lines[line + 1]:sub(character + 1, character + data[i + 2]) .. ':'
      .. legend.tokenTypes[data[i + 3] + 1] .. (#modifiers > 0 and '+' .. table.concat(modifiers, '+') or ''))
  end
end
log('semantic tokens line 15', table.concat(decoded, ' '))

local f = io.open(vim.env.OUT, 'w'); f:write(table.concat(out, '\n') .. '\n'); f:close()
vim.cmd('qa!')
