# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Pure public-rectangle selection. Ownership and input transport are caller guards.

Accessibility evidence can intersect the screen without fitting inside it.
Action targets are never clipped or clamped into acceptability.
"""


def require(value, code):
    if not value:
        raise RuntimeError(code)


def _dimensions(state):
    width, height = state.get("screenWidth"), state.get("screenHeight")
    require(type(width) is int and type(height) is int and width > 0 and height > 0
            and type(state.get("nodes")) is list, "native_ui_invalid")
    return width, height


def bounds_of(row, width=2160, height=1440, *, for_input=True):
    bounds = row.get("bounds")
    require(type(bounds) is list and len(bounds) == 4 and all(type(v) is int for v in bounds),
            "native_bounds_invalid")
    left, top, right, bottom = bounds
    require(left < right and top < bottom and right > 0 and bottom > 0
            and left < width and top < height, "native_bounds_invalid")
    if for_input:
        require(0 <= left < right <= width and 0 <= top < bottom <= height,
                "native_target_not_fully_visible")
    return bounds


def _matching(state, key, clickable):
    for row in state["nodes"]:
        require(type(row) is dict, "native_ui_invalid")
        # A non-target label cannot invalidate a separate action control.
        if (row.get("key") == key and row.get("clickable") is clickable
                and row.get("editable") is False and row.get("password") is not True
                and "checked" not in row):
            yield row


def dock_top(state):
    width, height = _dimensions(state)
    dock = []
    for key in ("home", "apps", "tools"):
        matches = []
        for row in _matching(state, key, True):
            bounds = bounds_of(row, width, height, for_input=False)
            if bounds[1] >= height // 2:
                matches.append(bounds_of(row, width, height))
        require(len(matches) == 1, "native_target_missing")
        dock.append(matches[0])
    require(dock[0][2] <= dock[1][0] and dock[1][2] <= dock[2][0]
            and max(r[1] for r in dock) < min(r[3] for r in dock),
            "native_bounds_invalid")
    return min(r[1] for r in dock)


def rail_edge(state, keys=("toolsNotes", "toolsConverter")):
    width, height = _dimensions(state)
    top = dock_top(state)
    require(len(keys) >= 2 and len(set(keys)) == len(keys), "native_rail_keys_invalid")
    rail = []
    for key in keys:
        matches = []
        for row in _matching(state, key, True):
            bounds = bounds_of(row, width, height, for_input=False)
            if bounds[3] <= top:
                matches.append(bounds_of(row, width, height))
        require(len(matches) == 1, "native_target_missing")
        rail.append(matches[0])
    require(all(r[0] == rail[0][0] and r[2] == rail[0][2] for r in rail)
            and 0 < rail[0][0] < rail[0][2] < width // 2, "native_bounds_invalid")
    ordered = sorted(rail, key=lambda r: r[1])
    require(all(first[3] <= second[1] for first, second in zip(ordered, ordered[1:])),
            "native_rail_overlap")
    return rail[0][2]


def targets(state, key, region, clickable):
    require(type(clickable) is bool and region in ("bottom", "body", "left", "right"),
            "native_region_invalid")
    width, height = _dimensions(state)
    dock = dock_top(state)
    edge = rail_edge(state) if region in ("left", "right") else None
    found = []
    for row in _matching(state, key, clickable):
        left, top, right, bottom = bounds_of(row, width, height, for_input=False)
        if region == "bottom" and top < dock:
            continue
        if region == "body" and bottom > dock:
            continue
        if region == "left" and (right > edge or bottom > dock):
            continue
        if region == "right" and (left <= edge or bottom > dock):
            continue
        if clickable:
            bounds_of(row, width, height)  # No coordinate normalization for inputs.
        found.append(row)
    require(len(found) <= 1, "native_target_missing")
    return found
