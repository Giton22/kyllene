import { A2uiSurface, basicCatalog } from '@a2ui/lit/v0_9'
import { MessageProcessor } from '@a2ui/web_core/v0_9'

customElements.get('a2ui-surface') || customElements.define('a2ui-surface', A2uiSurface)

const card = document.querySelector('main')
let processor
let applied = []
let takesTaps = false
let sequence = 0

function report(message) {
  window.cefQuery({
    request: JSON.stringify({ sequence, ...message }),
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

function reset() {
  processor?.dispose()
  processor = new MessageProcessor([basicCatalog], answer)
  applied = []
  card.replaceChildren()
  processor.onSurfaceCreated(surface => {
    const element = document.createElement('a2ui-surface')
    element.surface = surface
    card.append(element)
  })
  processor.onSurfaceDeleted(id => {
    Array.from(card.children).find(element => element.surface?.id === id)?.remove()
  })
}

reset()
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

window.renderAgentScreen = async (messages, allowed, nextSequence) => {
  sequence = nextSequence
  takesTaps = allowed
  card.inert = !allowed
  try {
    if (!applied.every((message, index) => message === messages[index])) reset()
    const fresh = messages.slice(applied.length).map(message => JSON.parse(message))
    const errors = fresh.length ? processor.processMessagesWithErrors(fresh) : []
    if (errors.length) throw new Error('Invalid screen')
    applied = messages
    await Promise.all(Array.from(card.children, element => element.updateComplete))
    if (sequence !== nextSequence) return
    reportLayout()
    if (Array.from(card.children).some(element => element.rootNode)) report({ type: 'shown' })
    else if (fresh.some(message => message.deleteSurface)) report({ type: 'shown' })
  } catch {
    if (sequence === nextSequence) report({ type: 'error' })
  }
}
