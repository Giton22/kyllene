import assert from 'node:assert/strict'
import test from 'node:test'
import { getValue, NodeResolver } from '@a2ui/web_core/v0_9'

const catalogId = 'https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json'
const create = surfaceId => ({ createSurface: { surfaceId, catalogId, sendDataModel: true } })
const root = surfaceId => ({
  updateComponents: { surfaceId, components: [{ id: 'root', component: 'TextField', label: 'Name', value: { path: '/name' } }] },
})
const data = (surfaceId, path, value) => ({ updateDataModel: { surfaceId, path, value } })
const close = surfaceId => ({ deleteSurface: { surfaceId } })
let pageId = 0

async function openPage(updateComplete = Promise.resolve()) {
  const reports = []
  const children = []
  const card = {
    children,
    append(element) { children.push(element) },
    replaceChildren() { children.splice(0) },
    getBoundingClientRect() { return { height: 48 } },
  }
  globalThis.window = {
    cefQuery({ request }) { reports.push(JSON.parse(request)) },
    addEventListener() {},
  }
  globalThis.document = {
    querySelector() { return card },
    createElement() {
      return {
        updateComplete,
        remove() { children.splice(children.indexOf(this), 1) },
        get rootNode() {
          const resolver = new NodeResolver(this.surface)
          try {
            return getValue(resolver.rootNode)
          } finally {
            resolver.dispose()
          }
        },
      }
    },
  }
  const registered = new Map()
  globalThis.customElements = registered
  registered.define = registered.set.bind(registered)
  globalThis.ResizeObserver = class { observe() {} }
  await import('./screen.js?page=' + ++pageId)
  const renderAgentScreen = window.renderAgentScreen
  let sequence = 0
  return {
    card,
    reports,
    surface(id) { return children.find(element => element.surface.id === id)?.surface },
    render(messages, draft, validateOnly = false) {
      return renderAgentScreen(messages.map(JSON.stringify), true, ++sequence, draft, validateOnly)
    },
  }
}

test('rejected update preserves the visible form and its edits', async () => {
  const page = await openPage()
  const initial = [create('one'), root('one'), data('one', '/name', 'bot')]
  await page.render(initial)
  const original = page.surface('one')
  original.dataModel.set('/name', 'user')
  await page.render([...initial, data('one', '/name', 'discarded'), data('missing', '/name', 'invalid')])
  assert.equal(page.reports.at(-1).type, 'error')
  assert.equal(page.surface('one'), original)
  assert.equal(original.dataModel.get('/name'), 'user')
  assert.equal(page.card.inert, true)
  await page.render([...initial, data('one', '/status', 'saved')])
  assert.equal(page.surface('one'), original)
  assert.equal(original.dataModel.get('/name'), 'user')
  assert.equal(original.dataModel.get('/status'), 'saved')
})

test('rejected history replacement preserves the current surfaces', async () => {
  const page = await openPage()
  await page.render([create('one'), root('one')])
  const original = page.surface('one')
  original.dataModel.set('/name', 'user')
  await page.render([create('two'), root('two'), data('missing', '/name', 'invalid')])
  assert.equal(page.reports.at(-1).type, 'error')
  assert.equal(Boolean(page.surface('two')), false)
  assert.equal(page.surface('one'), original)
  assert.equal(original.dataModel.get('/name'), 'user')
})

test('detached validation uses the current edited model', async () => {
  const page = await openPage()
  const initial = [create('one'), root('one'), data('one', '/name', { part: 'bot' })]
  await page.render(initial)
  const model = page.surface('one').dataModel
  model.set('/name', 'user')
  await page.render([...initial, data('one', '/status', 'discarded'), data('one', '/name/part', 'server')])
  assert.equal(page.reports.at(-1).type, 'error')
  assert.equal(model.get('/name'), 'user')
  assert.equal(model.get('/status'), undefined)
})

test('a draft crosses page mounts and newer server writes win', async () => {
  const first = await openPage()
  const initial = [create('one'), root('one'), data('one', '/name', 'bot'), data('one', '/status', 'old')]
  await first.render(initial)
  first.surface('one').dataModel.set('/name', 'user')
  const draft = first.reports.findLast(report => report.type === 'draft')
  assert.ok(draft, 'an edit reports its draft to the host')
  assert.deepEqual(draft.models, { one: { name: 'user', status: 'old' } })

  const second = await openPage()
  await second.render([...initial, data('one', '/status', 'new')], { prefix: initial.length, models: draft.models })
  assert.deepEqual(second.surface('one').dataModel.get(''), { name: 'user', status: 'new' })
})

test('a stale draft cannot block newer valid server data', async () => {
  const page = await openPage()
  const initial = [create('one'), root('one'), data('one', '/name', { part: 'bot' })]
  await page.render([...initial, data('one', '/name/part', 'server')], {
    prefix: initial.length,
    models: { one: { name: 'stale' } },
  })
  assert.deepEqual(page.surface('one').dataModel.get(''), { name: { part: 'server' } })
  assert.equal(page.reports.at(-1).type, 'shown')
})

test('only rendered roots and explicit final deletes are acknowledged', async () => {
  const page = await openPage()
  const initial = [create('visible'), root('visible'), create('pending'), create('closed'), root('closed'), close('closed')]
  await page.render(initial)
  assert.deepEqual(page.reports.at(-1), { sequence: 1, type: 'shown', surfaces: ['visible', 'closed'] })
  await page.render([...initial, create('closed')])
  assert.deepEqual(page.reports.at(-1), { sequence: 2, type: 'shown', surfaces: ['visible'] })
})

test('validation can acknowledge a proposal without applying it', async () => {
  const page = await openPage()
  const initial = [create('one'), root('one'), data('one', '/name', 'bot')]
  await page.render(initial)
  const model = page.surface('one').dataModel
  model.set('/name', 'user')
  const proposal = [...initial, data('one', '/status', 'new'), create('two'), root('two')]
  await page.render(proposal, undefined, true)
  assert.equal(model.get('/status'), undefined)
  assert.equal(Boolean(page.surface('two')), false)
  assert.equal(page.card.inert, true)
  assert.deepEqual(page.reports.at(-1), { sequence: 2, type: 'validated', surfaces: ['one', 'two'] })
  await page.render(proposal)
  assert.deepEqual(model.get(''), { name: 'user', status: 'new' })
  assert.equal(page.card.inert, false)
  assert.deepEqual(page.reports.at(-1), { sequence: 3, type: 'shown', surfaces: ['one', 'two'] })
})

test('remount validation uses the cached draft without publishing a live surface', async () => {
  const page = await openPage()
  const initial = [create('one'), root('one'), data('one', '/name', 'bot')]
  const draft = { prefix: initial.length, models: { one: { name: 'user' } } }
  const proposal = [...initial, data('one', '/status', 'new')]
  await page.render(proposal, draft, true)
  assert.equal(Boolean(page.surface('one')), false)
  assert.deepEqual(page.reports.at(-1), { sequence: 1, type: 'validated', surfaces: ['one'] })
  await page.render(proposal, draft)
  assert.deepEqual(page.surface('one').dataModel.get(''), { name: 'user', status: 'new' })
})

test('a late render completion cannot acknowledge or unlock a newer validation phase', async () => {
  const { promise, resolve } = Promise.withResolvers()
  const page = await openPage(promise)
  const initial = [create('one'), root('one')]
  const firstRender = page.render(initial)
  await page.render([...initial, data('one', '/status', 'new')], undefined, true)
  resolve()
  await firstRender
  assert.equal(page.card.inert, true)
  assert.deepEqual(page.reports, [{ sequence: 2, type: 'validated', surfaces: ['one'] }])
})

test('rolling back to a saved history restores its local edit', async () => {
  const page = await openPage()
  const initial = [create('one'), root('one'), data('one', '/name', 'bot')]
  await page.render(initial)
  page.surface('one').dataModel.set('/name', 'user')
  const draft = { prefix: initial.length, models: page.reports.findLast(report => report.type === 'draft').models }
  await page.render([...initial, data('one', '/name', 'proposal')], draft)
  assert.equal(page.surface('one').dataModel.get('/name'), 'proposal')
  await page.render(initial, draft)
  assert.equal(page.surface('one').dataModel.get('/name'), 'user')
})

test('an oversized form answer reports the limit and can be retried', async () => {
  const page = await openPage()
  await page.render([create('one'), root('one'), data('one', '/name', 'x'.repeat(64 * 1024))])
  const surface = page.surface('one')
  await surface.dispatchAction({ event: { name: 'submit' } }, 'button')
  assert.deepEqual(page.reports.at(-1), { sequence: 1, type: 'answerTooLarge' })
  assert.equal(page.card.inert, false)
  surface.dataModel.set('/name', 'shorter')
  await surface.dispatchAction({ event: { name: 'submit' } }, 'button')
  assert.equal(page.reports.at(-1).type, 'answer')
  assert.deepEqual(page.reports.at(-1).result.data, { name: 'shorter' })
})

test('an oversized draft reports the limit without sending input data', async () => {
  const page = await openPage()
  await page.render([create('one'), root('one')])
  const model = page.surface('one').dataModel
  model.set('/name', 'earlier edit')
  assert.equal(page.reports.at(-1).type, 'draft')
  model.set('/name', 'x'.repeat(64 * 1024))
  assert.deepEqual(page.reports.at(-1), { sequence: 1, type: 'draftTooLarge' })
  model.set('/name', 'shorter')
  assert.deepEqual(page.reports.at(-1), { sequence: 1, type: 'draft', models: { one: { name: 'shorter' } } })
})
