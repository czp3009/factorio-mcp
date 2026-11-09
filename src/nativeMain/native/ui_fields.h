#pragma once
#include <stdint.h>

// Optional result paths, in the same order as common UiField. Identity/structure/own flags remain unconditional.
enum FmUiField : uint32_t {
    FM_UI_TEXT = 1u << 0,
    FM_UI_CHECK_STATE = 1u << 1,
    FM_UI_TOGGLED = 1u << 2,
    FM_UI_TOGGLE_MODE = 1u << 3,
    FM_UI_SELECTED_INDEX = 1u << 4,
    FM_UI_VALUE = 1u << 5,
    FM_UI_MINIMUM = 1u << 6,
    FM_UI_MAXIMUM = 1u << 7,
    FM_UI_STEP = 1u << 8,
    FM_UI_SLIDER = FM_UI_VALUE | FM_UI_MINIMUM | FM_UI_MAXIMUM | FM_UI_STEP,
    FM_UI_OPTIONS = 1u << 9,
    FM_UI_PROTOTYPE = 1u << 10,
    FM_UI_NUMBER = 1u << 11,
    FM_UI_PROGRESS = 1u << 12,
    FM_UI_QUALITY = 1u << 13,
    FM_UI_ELEMENT = 1u << 14,
    FM_UI_ICONS = 1u << 15,
    FM_UI_QUALITY_CONDITION = 1u << 16,
    FM_UI_SWITCH = 1u << 17,
    FM_UI_TOOLTIP = 1u << 18,
    FM_UI_GUI_PROPERTIES = 1u << 19,
    FM_UI_GUI_CHILDREN = 1u << 20,
    FM_UI_IMAGE = 1u << 21,
    FM_UI_GRAPH = 1u << 22,
    FM_UI_ACHIEVEMENT = 1u << 23,
    FM_UI_ALL_FIELDS = (1u << 24) - 1,
};
