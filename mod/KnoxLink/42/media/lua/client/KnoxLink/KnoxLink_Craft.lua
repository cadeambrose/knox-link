--[[
    Knox Link — what can I make?

    The same recipes the game's crafting window offers by hand (and on a surface next to you),
    checked against what you carry and what's around you by the game's own crafting logic, so
    the list only shows what you can make right now. One tap starts it like the window would.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try = KL.try

local QUERY = "InHandCraft;AnySurfaceCraft"   -- ISHandcraftWindow's own recipe list
local logic, logicPlayer

local function prepare(player)
    if not (HandcraftLogic and CraftRecipeManager and ISInventoryPaneContextMenu) then return nil end
    if not logic or logicPlayer ~= player then
        logic = HandcraftLogic.new(player, nil, nil)
        logic:setRecipes(CraftRecipeManager.queryRecipes(QUERY))
        logicPlayer = player
    end
    local surface = ISEntityUI and ISEntityUI.FindCraftSurface and ISEntityUI.FindCraftSurface(player, 2) or nil
    logic:setIsoObject(surface)
    logic:setContainers(ISInventoryPaneContextMenu.getContainers(player))
    return logic
end

local function craftable(l, recipe)
    local info = l:getCachedRecipeInfo(recipe)
    return info ~= nil and info:isValid() and info:isCanPerform()
end

KL.addCollector("craft", 3000, function(player)
    local l = prepare(player)
    if not l then return { off = true } end
    local recipes = CraftRecipeManager.queryRecipes(QUERY)
    local out = {}
    for i = 0, recipes:size() - 1 do
        local r = recipes:get(i)
        if craftable(l, r) then
            table.insert(out, {
                id = r:getName(),
                n = r:getTranslationName() or r:getName(),
                ic = try(r, "getIconName", nil),
                cat = try(r, "getCategory", nil),
                t = try(r, "getTime", nil),
            })
        end
        if #out >= 150 then break end
    end
    table.sort(out, function(a, b)
        if tostring(a.cat) ~= tostring(b.cat) then return tostring(a.cat) < tostring(b.cat) end
        return tostring(a.n) < tostring(b.n)
    end)
    return { list = out }
end)

KL.addHandler("craft", function(player, a)
    local l = prepare(player)
    if not l then return false, "Crafting isn't available in this version" end
    local recipes = CraftRecipeManager.queryRecipes(QUERY)
    local recipe = nil
    for i = 0, recipes:size() - 1 do
        if recipes:get(i):getName() == a.id then recipe = recipes:get(i) end
    end
    if not recipe then return false, "Unknown recipe" end
    if not craftable(l, recipe) then return false, "You can't make that right now" end
    l:setRecipe(recipe)
    try(l, "autoPopulateInputs", nil)
    local action = ISEntityUI.HandcraftStart(player, l, false, true)
    if not action then return false, "You can't make that here" end
    -- the next request gets a fresh logic: this one now belongs to the running action
    logic = nil
    KL.refresh("craft")
    return true, recipe:getTranslationName() or recipe:getName()
end)
