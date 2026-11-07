#include "layout.h"

#ifdef PBL_ROUND
static int prv_sqrt(int n) {
  int root = 0;
  while ((root + 1) * (root + 1) <= n) root++;
  return root;
}

// How far in from the side the screen's edge is at height [y].
static int prv_edge(GRect screen, int y) {
  int r = screen.size.w / 2;
  int d = y - screen.size.h / 2;
  if (d < 0) d = -d;
  return d >= r ? r : r - prv_sqrt(r * r - d * d);
}
#endif

int layout_inset(GRect screen, int top, int height, int pad) {
#ifdef PBL_ROUND
  int top_edge = prv_edge(screen, top);
  int bottom_edge = prv_edge(screen, top + height);
  return pad + (top_edge > bottom_edge ? top_edge : bottom_edge);
#else
  return pad;
#endif
}

void layout_draw_header(GContext *ctx, GRect bounds, const char *title, GColor fill, GColor on_fill, int right_room) {
  graphics_context_set_fill_color(ctx, fill);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
  graphics_context_set_text_color(ctx, on_fill);
  int top = bounds.size.h - 26;
  // At the top of the screen and as wide: a round screen is as tall as it's wide.
  int inset = layout_inset(GRect(0, 0, bounds.size.w, bounds.size.w), top, 22, PBL_IF_ROUND_ELSE(2, 6));
  graphics_draw_text(ctx, title, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                     GRect(inset, top, bounds.size.w - inset * 2 - PBL_IF_ROUND_ELSE(0, right_room), 22),
                     GTextOverflowModeTrailingEllipsis, LAYOUT_ALIGN, NULL);
}

// The flow takes the layer's place as it is when turned on: on again once it moves.
static void prv_flow(TextLayer *layer, ScrollLayer *scroll) {
#ifdef PBL_ROUND
  text_layer_set_text_alignment(layer, GTextAlignmentCenter);
  text_layer_enable_screen_text_flow_and_paging(layer, 4);
  scroll_layer_set_paging(scroll, true);
#endif
}

int layout_fit(TextLayer *layer, ScrollLayer *scroll, int x, int y, int w) {
  const char *text = text_layer_get_text(layer);
  if (!text || !text[0]) {
    layer_set_frame(text_layer_get_layer(layer), GRect(x, y, w, 0));
    return 0;
  }
  layer_set_frame(text_layer_get_layer(layer), GRect(x, y, w, 2000));
  prv_flow(layer, scroll);
  int h = text_layer_get_content_size(layer).h + 6;
  layer_set_frame(text_layer_get_layer(layer), GRect(x, y, w, h));
  prv_flow(layer, scroll);
  return h;
}
