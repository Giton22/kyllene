import { A2uiSurface, basicCatalog } from '@a2ui/lit/v0_9'
import { getValue, MessageProcessor, NodeResolver } from '@a2ui/web_core/v0_9'

customElements.get('a2ui-surface') || customElements.define('a2ui-surface', A2uiSurface)

const card = document.querySelector('main')
let processor
let applied = []
let closed = []
let takesTaps = false
let sequence = 0
const maxQueryChars = 64 * 1024

function report(message) {
  let request = JSON.stringify({ sequence, ...message })
  if (request.length > maxQueryChars) {
    if (message.type !== 'answer' && message.type !== 'draft') return
    request = JSON.stringify({ sequence, type: message.type + 'TooLarge' })
  }
  window.cefQuery({
    request,
    onSuccess() {},
    onFailure() {},
  })
}

function answer(action) {
  if (!takesTaps) return
  const surface = processor.getSurface(action.surfaceId)
  if (!surface) return
  report({
    type: 'answer',
    surface: surface.id,
    result: {
      event: action.name,
      surface_id: surface.id,
      component_id: action.sourceComponentId,
      timestamp: action.timestamp,
      context: action.context,
      ...(surface.sendDataModel ? { data: surface.dataModel.get('') } : {}),
    },
  })
}

function models() {
  return Object.fromEntries(Array.from(processor.getSurfaces(), ([id, surface]) => [id, surface.dataModel.get('')]))
}

function mount(next) {
  processor?.dispose()
  card.replaceChildren()
  processor = next
  function add(surface) {
    const element = document.createElement('a2ui-surface')
    element.surface = surface
    card.append(element)
    surface.dataModel.subscribe('', () => {
      if (takesTaps) report({ type: 'draft', models: models() })
    })
  }
  processor.onSurfaceCreated(add)
  processor.onSurfaceDeleted(id => {
    Array.from(card.children).find(element => element.surface?.id === id)?.remove()
  })
  for (const surface of processor.getSurfaces().values()) add(surface)
}

function apply(target, messages) {
  if (messages.length && target.processMessagesWithErrors(messages).length) {
    throw new Error('Invalid screen')
  }
}

function prepare(messages, draft) {
  const candidate = new MessageProcessor([basicCatalog], answer)
  try {
    const prefix = draft?.prefix ?? 0
    apply(candidate, messages.slice(0, prefix))
    if (draft) {
      for (const [id, data] of Object.entries(draft.models)) candidate.getSurface(id)?.dataModel.set('', data)
    }
    apply(candidate, messages.slice(prefix))
    return candidate
  } catch (error) {
    candidate.dispose()
    throw error
  }
}

function canRender(surface) {
  const resolver = new NodeResolver(surface)
  try {
    const root = getValue(resolver.rootNode)
    return root && !root.isPlaceholder
  } finally {
    resolver.dispose()
  }
}

function reportLayout() {
  const height = Math.ceil(card.getBoundingClientRect().height)
  report({
    type: 'layout',
    height,
    above: window.scrollY > 0,
    below: window.scrollY + window.innerHeight < height,
  })
}

new ResizeObserver(reportLayout).observe(card)
window.addEventListener('resize', reportLayout)
window.addEventListener('scroll', reportLayout)

window.setAgentScreenTheme = theme => {
  for (const [name, value] of Object.entries(theme)) {
    document.documentElement.style.setProperty(name, value)
  }
}

window.renderAgentScreen = async (messages, allowed, nextSequence, savedDraft, validateOnly) => {
  sequence = nextSequence
  takesTaps = false
  card.inert = true
  let candidate
  try {
    const extendsApplied = processor && applied.every((message, index) => message === messages[index])
    if (validateOnly || !extendsApplied || messages.length !== applied.length) {
      const parsed = messages.map(message => JSON.parse(message))
      const draft = extendsApplied ? { prefix: applied.length, models: models() } : savedDraft
      try {
        candidate = prepare(parsed, draft)
      } catch (error) {
        // An edit saved at an older revision can conflict with a newer server path.
        if (!draft || extendsApplied) throw error
        candidate = prepare(parsed)
      }
      const deleted = Array.from(new Set(parsed.map(message => message.deleteSurface?.surfaceId)
        .filter(id => typeof id === 'string' && !candidate.getSurface(id))))
      if (validateOnly) {
        const surfaces = Array.from(candidate.getSurfaces().values()).filter(canRender).map(surface => surface.id)
        report({ type: 'validated', surfaces: [...surfaces, ...deleted] })
        return
      }
      if (extendsApplied) {
        // Preserve the mounted controls across incremental updates.
        apply(processor, parsed.slice(applied.length))
      } else {
        mount(candidate)
        candidate = undefined
      }
      applied = messages
      closed = deleted
    }
    await Promise.all(Array.from(card.children, element => element.updateComplete))
    if (sequence !== nextSequence) return
    const surfaces = Array.from(card.children)
      .filter(element => element.rootNode && !element.rootNode.isPlaceholder)
      .map(element => element.surface.id)
    takesTaps = allowed
    card.inert = !allowed
    reportLayout()
    if (surfaces.length || closed.length) report({ type: 'shown', surfaces: [...surfaces, ...closed] })
  } catch {
    if (sequence === nextSequence) report({ type: 'error' })
  } finally {
    candidate?.dispose()
  }
}
