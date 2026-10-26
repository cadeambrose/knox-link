--[[
    Knox Link — the game's own right-click menus, on the second screen.

    An item's menu (what right-clicking it in the inventory offers) and a world object's menu
    (a door, a window, curtains, a chair, a light switch...) are built by the game's own code into
    a menu that records instead of showing; a screen gets the options, and the chosen one runs
    through the game's own callback. So every action the game has is there, with its rules
    (greyed out with the game's reason when it can't be done), and nothing it doesn't have.

    Also: the things around you worth a menu, and moving items between your bags, the containers
    around you and the floor with the game's transfer action.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try = KL.try

-- ----------------------------------------------------------------------------- reading the game's menu

-- The game's own context menu is built (by its Lua and, for the world, its Java code), read into a
-- plain list, and hidden again in the same frame, before it's ever drawn (Project Viewpoint's 3D
-- "interact" works the same way). Halo texts the builders may show stay quiet meanwhile.
local HALO = { "addText", "addGoodText", "addBadText", "addTextWithArrow" }

local function quietly(build, playerNum)
    local saved = {}
    for _, n in ipairs(HALO) do
        saved[n] = HaloTextHelper[n]
        HaloTextHelper[n] = function() end
    end
    local debug = DebugContextMenu and DebugContextMenu.doDebugMenu
    if debug then DebugContextMenu.doDebugMenu = function() end end
    local ok, err = pcall(build, playerNum)
    for _, n in ipairs(HALO) do HaloTextHelper[n] = saved[n] end
    if debug then DebugContextMenu.doDebugMenu = debug end
    return ok, err
end

local DEBUG_ICON = nil

-- One menu level as { options = { {name, target, onSelect, args, nargs, sub, notAvailable, toolTip, ...} } }.
local function readMenu(menu, depth)
    local out = { options = {} }
    if not menu or not menu.options then return out end
    DEBUG_ICON = DEBUG_ICON or getTexture("media/textures/Item_Plumpabug_Left.png")
    for i = 1, (menu.numOptions or 1) - 1 do
        local o = menu.options[i]
        if o and not o.isDefaultOption then
            local e = {
                name = o.name, target = o.target, onSelect = o.onSelect,
                args = { o.param1, o.param2, o.param3, o.param4, o.param5, o.param6, o.param7, o.param8, o.param9, o.param10 },
                nargs = 10, notAvailable = o.notAvailable, toolTip = o.toolTip, itemForTexture = o.itemForTexture,
                checkMark = o.checkMark, debug = DEBUG_ICON ~= nil and o.iconTexture == DEBUG_ICON,
            }
            if o.subOption and depth < 4 and menu.getSubMenu then
                local sub = menu:getSubMenu(o.subOption)
                if sub then e.sub = readMenu(sub, depth + 1) end
            end
            table.insert(out.options, e)
        end
    end
    return out
end

--- Runs build(playerNum) (which fills the game's context menu for that player), reads the menu and
--- hides it. Returns the menu as a plain list, or nil.
function KL.recordMenu(playerNum, build)
    local menu = getPlayerContextMenu and getPlayerContextMenu(playerNum)
    if not menu then return nil end
    local ok, err = quietly(build, playerNum)
    local rec = ok and readMenu(getPlayerContextMenu(playerNum), 1) or nil
    pcall(function() getPlayerContextMenu(playerNum):hideAndChildren() end)
    if not ok then
        KL.warn("menu", tostring(err))
        return nil
    end
    rec.player = playerNum
    return rec
end
local function tooltipText(o)
    local tt = o.toolTip
    if type(tt) ~= "table" then return nil end
    local d = tt.description
    if type(d) ~= "string" or d == "" then return nil end
    -- the game's rich text: <RGB:1,0,0> tags, <LINE> breaks
    d = d:gsub("<LINE>", " "):gsub("<BR>", " "):gsub("<[^>]*>", ""):gsub("%s+", " ")
    return d:match("^%s*(.-)%s*$")
end

--- The recorded menu as a tree for a screen: { {t = text, d = disabled reason or true, s = {children}}, ... }.
--- Debug options and empty labels are left out.
local function tree(rec, depth)
    local out = {}
    for _, o in ipairs(rec.options) do
        local name = o.name
        if not o.debug and type(name) == "string" and name ~= "" then
            local e = { t = name }
            if o.notAvailable then e.d = tooltipText(o) or true end
            if o.sub and depth < 3 then
                e.s = tree(o.sub, depth + 1)
                if #e.s == 0 then e = nil end
            elseif not o.onSelect then
                e = nil
            end
            if e then
                if o.itemForTexture then e.ic = KL.iconOf(o.itemForTexture) end
                if o.checkMark then e.c = true end
                table.insert(out, e)
            end
        end
    end
    return out
end

--- Finds an option by its path of labels (each step "text" or "text#n" for the n-th of that name).
local function find(rec, path)
    local menu, opt = rec, nil
    for _, step in ipairs(path) do
        if not menu then return nil end
        local name, nth = tostring(step):match("^(.-)#(%d+)$")
        name, nth = name or tostring(step), tonumber(nth) or 1
        opt = nil
        local seen = 0
        for _, o in ipairs(menu.options) do
            if o.name == name then
                seen = seen + 1
                if seen == nth then opt = o break end
            end
        end
        if not opt then return nil end
        menu = opt.sub
    end
    return opt
end

local function run(opt, playerNum)
    if not opt or not opt.onSelect or opt.notAvailable then return false end
    -- some of the game's callbacks ask which player's menu they came from
    if ISContextMenu then ISContextMenu.globalPlayerContext = playerNum end
    opt.onSelect(opt.target, unpack(opt.args, 1, opt.nargs))
    return true
end

-- ----------------------------------------------------------------------------- whose menu

local function paused()
    return UIManager.getSpeedControls():getCurrentGameSpeed() == 0
end

-- The containers the game's loot window lists, plus the bodies nearby (KnoxLink_Loot keeps both).
local function itemsFor(player, a)
    local ids = type(a.ids) == "table" and a.ids or { a.id }
    local list = {}
    for _, id in ipairs(ids) do
        local it = KL.findItem(player, id) or (KL.findAround and KL.findAround(player, id))
        if it then table.insert(list, it) end
    end
    return list
end

local function itemMenu(player, a)
    local items = itemsFor(player, a)
    if #items == 0 then return nil, "That item isn't there anymore" end
    local inPlayerInv = items[1]:getContainer() and items[1]:getContainer():isInCharacterInventory(player) or false
    -- the game's own format: one item, or a stack as { items = { first, first, second, ... } }
    local arg
    if #items == 1 then
        arg = { items[1] }
    else
        local stack = { items[1] }
        for _, it in ipairs(items) do table.insert(stack, it) end
        arg = { { items = stack } }
    end
    local rec = KL.recordMenu(player:getPlayerNum(), function(num)
        ISInventoryPaneContextMenu.createMenu(num, inPlayerInv, arg, 0, 0, nil)
    end)
    return rec, items[1]:getDisplayName()
end

-- The interesting things on the squares around you: doors, windows, curtains, light switches,
-- furniture... anything with a name the game shows.
local function objectName(o)
    if instanceof(o, "IsoDoor") then return "Door" end
    if instanceof(o, "IsoWindow") then return getTextOrNull("ContextMenu_Window") or "Window" end
    if instanceof(o, "IsoCurtain") then return "Curtains" end
    if instanceof(o, "IsoLightSwitch") then return getTextOrNull("Light_Switch") or "Light switch" end
    local props = try(o, "getProperties", nil)
    if props then
        local n = try(props, "Val", nil, "CustomName")
        if n and n ~= "" then
            local g = try(props, "Val", nil, "GroupName")
            local full = (g and g ~= "") and (g .. " " .. n) or n
            return getTextOrNull("Moveable_" .. full:gsub(" ", "_")) or full
        end
    end
    local nm = try(o, "getName", nil)
    if type(nm) == "string" and nm ~= "" and nm ~= "null" then return nm end
    return nil
end

local function objectAt(player, x, y, z, i)
    local sq = getCell():getGridSquare(tonumber(x) or 0, tonumber(y) or 0, tonumber(z) or 0)
    if not sq then return nil end
    local objs = sq:getObjects()
    i = tonumber(i)
    if not i or i < 0 or i >= objs:size() then return nil end
    return objs:get(i), sq
end

-- The game's menu for one spot: like a right-click there, everything on that square gets its options.
local function objectMenu(player, a)
    local o, sq = objectAt(player, a.x, a.y, a.z, a.i)
    if not o then return nil, "It's not there anymore" end
    local num = player:getPlayerNum()
    local worldobjects, light = {}, nil
    local objs = sq:getObjects()
    for i = 0, objs:size() - 1 do
        local x = objs:get(i)
        table.insert(worldobjects, x)
        if instanceof(x, "IsoLightSwitch") then light = x end
    end
    local rec = KL.recordMenu(num, function()
        -- where the spot is on the game's screen, as a click on it would give
        local x = isoToScreenX(num, sq:getX(), sq:getY(), sq:getZ())
        local y = isoToScreenY(num, sq:getX(), sq:getY(), sq:getZ())
        ISWorldObjectContextMenu.createMenu(num, worldobjects, x, y, false)
    end)
    -- the game switches lights with a click on the switch, not from its menu: offered first here
    if rec and light and ISWorldObjectContextMenu.onToggleLight then
        local text = getText(light:isActivated() and "ContextMenu_Turn_Off" or "ContextMenu_Turn_On")
        table.insert(rec.options, 1, { name = text, target = nil, onSelect = ISWorldObjectContextMenu.onToggleLight,
            args = { light, num }, nargs = 2 })
    end
    return rec, a.n or objectName(o) or "Object"
end

local RADIUS = 3

--- The spots within a few squares with something worth a menu, nearest first: { {n = "Window, Light switch", x, y, z, i, d}, ... }
--- (the game's menu is per square, so one row per spot with the names of what's there).
function KL.nearbyObjects(player)
    local px, py, pz = math.floor(player:getX()), math.floor(player:getY()), math.floor(player:getZ())
    local out = {}
    local cell = getCell()
    for y = py - RADIUS, py + RADIUS do
        for x = px - RADIUS, px + RADIUS do
            local sq = cell:getGridSquare(x, y, pz)
            if sq then
                local objs = sq:getObjects()
                local names, seen, first = {}, {}, nil
                for i = 0, objs:size() - 1 do
                    local o = objs:get(i)
                    -- floors and items lying on the floor are covered elsewhere
                    if not try(o, "isFloor", false) and not instanceof(o, "IsoWorldInventoryObject") then
                        local n = objectName(o)
                        if n and not seen[n] then
                            seen[n] = true
                            table.insert(names, n)
                            first = first or i
                        end
                    end
                end
                if first then
                    local dx, dy = x + 0.5 - player:getX(), y + 0.5 - player:getY()
                    table.insert(out, { n = table.concat(names, ", "), x = x, y = y, z = pz, i = first, d = math.sqrt(dx * dx + dy * dy) })
                end
            end
        end
    end
    table.sort(out, function(p, q) return p.d < q.d end)
    while #out > 24 do table.remove(out) end
    for _, e in ipairs(out) do e.d = KL.round(e.d, 1) end
    return out
end

-- ----------------------------------------------------------------------------- screens

-- A screen asks for a menu; it goes out once on the "menu" channel with the screen's request number.
KL.addHandler("menu", function(player, a)
    if paused() then return false, "Unpause the game to use this" end
    local rec, title
    if a.kind == "obj" then rec, title = objectMenu(player, a) else rec, title = itemMenu(player, a) end
    if not rec then return false, title end
    local opts = tree(rec, 1)
    if KnoxLink_publish then KnoxLink_publish("menu", { req = tonumber(a.req) or 0, title = title, opts = opts }) end
    return true
end)

-- The screen chose an option: the menu is built again (things may have changed) and its callback runs.
KL.addHandler("menuDo", function(player, a)
    if paused() then return false, "Unpause the game to use this" end
    if type(a.path) ~= "table" or #a.path == 0 then return false, "Nothing chosen" end
    local rec, title
    if a.kind == "obj" then rec, title = objectMenu(player, a) else rec, title = itemMenu(player, a) end
    if not rec then return false, title end
    local opt = find(rec, a.path)
    if not opt then return false, "That can't be done now" end
    if opt.notAvailable then return false, tooltipText(opt) or "That can't be done now" end
    if not run(opt, player:getPlayerNum()) then return false, "That can't be done now" end
    KL.refresh()
    return true
end)

-- ----------------------------------------------------------------------------- moving items

--- Moves items (ids) to: "inv" (main inventory), "bag:<item id>" (a bag you carry or wear),
--- "near:<k>" (a container around you, by its place in the loot list) or "floor".
KL.addHandler("move", function(player, a)
    local ids = type(a.ids) == "table" and a.ids or { a.id }
    local to = tostring(a.to or "")
    local dest, bag
    if to == "inv" then
        dest = player:getInventory()
    elseif to:sub(1, 4) == "bag:" then
        bag = KL.findItem(player, tonumber(to:sub(5)))
        dest = bag and instanceof(bag, "InventoryContainer") and bag:getItemContainer() or nil
    elseif to:sub(1, 5) == "near:" or to == "floor" then
        dest = KL.nearContainer and KL.nearContainer(player, to) or nil
    end
    if not dest then return false, "That place isn't there anymore" end
    local moved, name, same, why = 0, nil, true, nil
    for _, id in ipairs(ids) do
        local item = KL.findItem(player, id) or (KL.findAround and KL.findAround(player, id))
        local src = item and item:getContainer()
        if item and src and src ~= dest then
            if item == bag or try(dest, "isInside", false, item) then
                why = "A bag can't go inside itself"
            elseif not try(dest, "isItemAllowed", true, item) then
                -- the game's own rule for that container: a key ring takes only keys
                why = bag and KL.isKeyRing(bag) and "Only keys go on a key ring" or "That can't go in there"
            elseif not try(dest, "hasRoomFor", true, player, item) then
                why = "It doesn't fit"
            elseif player:isEquipped(item) and not (player:getPrimaryHandItem() == item or player:getSecondaryHandItem() == item) then
                why = "Take it off first"   -- worn: the game wants it taken off before it goes anywhere
            else
                if player:getPrimaryHandItem() == item or player:getSecondaryHandItem() == item then
                    ISTimedActionQueue.add(ISUnequipAction:new(player, item, 50))
                end
                -- a container a few steps away (a body further off): walk there first, like the game does
                if KL.walkToContainer then KL.walkToContainer(player, src) ; KL.walkToContainer(player, dest) end
                ISTimedActionQueue.add(ISInventoryTransferUtil.newInventoryTransferAction(player, item, src, dest))
                moved = moved + 1
                local n = item:getDisplayName()
                if name and n ~= name then same = false end
                name = name or n
            end
        end
    end
    if moved == 0 then return false, why or "Nothing to move" end
    if moved == 1 then return true, name end
    -- "Nails ×24" for a stack, "9 items" for different things
    return true, same and (name .. " ×" .. moved) or (moved .. " items")
end)
