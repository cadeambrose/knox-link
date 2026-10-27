--[[
    Knox Link — "Ask": what an AI helper needs to know about your situation.

    The question goes from the screen to the AI the player set up for the journal (Claude,
    OpenAI, Gemini, OpenRouter or any OpenAI-compatible server); the game only supplies the
    situation, as a few lines of plain text, so the answer fits where you are and what you have.
    Only what the character knows: with immersion on, the time and the temperature need a watch,
    needs show as moodles, and nothing hidden (no infection) is ever told.

      ask  (every 3 s while the Ask sheet is open, when it changed): { text = "...", name = first name }
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try, round = KL.try, KL.round

local MONTHS = { "January", "February", "March", "April", "May", "June", "July", "August", "September",
    "October", "November", "December" }

local function collector(channel)
    for _, c in ipairs(KL.collectors) do
        if c.channel == channel then return c.fn end
    end
    return nil
end

local function join(list, sep)
    return table.concat(list, sep or ", ")
end

local function partOfDay(h)
    if h < 5 then return "night" elseif h < 9 then return "morning" elseif h < 12 then return "late morning"
    elseif h < 17 then return "afternoon" elseif h < 21 then return "evening" end
    return "night"
end

--- What the character carries, grouped by name: "Canned Beans x2, Bandage x3, ..." (bags count as theirs).
local function carried(player)
    local counts, order = {}, {}
    local p, s = player:getPrimaryHandItem(), player:getSecondaryHandItem()
    for _, it in ipairs(KL.allItems(player)) do
        if it ~= p and it ~= s and not try(it, "isHidden", false) and not player:isEquipped(it) then
            local n = it:getDisplayName()
            if instanceof(it, "Food") and try(it, "isRotten", false) then n = n .. " (rotten)" end
            if not counts[n] then
                counts[n] = 0
                table.insert(order, n)
            end
            counts[n] = counts[n] + 1
        end
    end
    local out = {}
    for _, n in ipairs(order) do
        table.insert(out, counts[n] > 1 and (n .. " x" .. counts[n]) or n)
        if #out >= 60 then
            table.insert(out, "...")
            break
        end
    end
    return out
end

local function worn(player)
    local out = {}
    local list = try(player, "getWornItems", nil)
    if not list then return out end
    for i = 0, list:size() - 1 do
        local it = list:getItemByIndex(i)
        if it and not try(it, "isHidden", false) then table.insert(out, it:getDisplayName()) end
        if #out >= 16 then break end
    end
    return out
end

local function heldText(item)
    if not item then return nil end
    local s = item:getDisplayName()
    local cmax = try(item, "getConditionMax", 0)
    if cmax and cmax > 0 then s = s .. " (condition " .. math.floor(item:getCondition() / cmax * 100 + 0.5) .. "%)" end
    return s
end

local function situation(player)
    local L = {}
    local function add(s) if s and s ~= "" then table.insert(L, s) end end
    local core = collector("core") and collector("core")(player) or {}
    local desc = player:getDescriptor()

    -- who
    local who = tostring(desc:getForename() or "") .. " " .. tostring(desc:getSurname() or "")
    local prof, traits = nil, {}
    if KL.whoIs then
        local okW, c, t = pcall(KL.whoIs, player)
        if okW then
            prof = c and c.jobLabel
            traits = t or {}
        end
    end
    local days = math.floor(player:getHoursSurvived() / 24) + 1
    add("Character: " .. who .. (prof and (", " .. prof) or "") .. (#traits > 0 and (". Traits: " .. join(traits)) or "")
        .. ". Day " .. days .. " of survival.")

    -- when
    local gt = getGameTime()
    local t = core.time
    if t and t.y then
        add("Date and time: " .. MONTHS[t.mo] .. " " .. t.d .. ", " .. t.y .. ", " .. string.format("%02d:%02d", t.h, t.mi)
            .. " (" .. partOfDay(t.h) .. ").")
    elseif t and t.h then
        add("Time: " .. string.format("%02d:%02d", t.h, t.mi) .. " (" .. partOfDay(t.h) .. "), " .. MONTHS[gt:getMonth() + 1] .. ".")
    else
        add("Time of day: " .. partOfDay(gt:getHour()) .. " (no watch), " .. MONTHS[gt:getMonth() + 1] .. ".")
    end

    -- where
    local sq = player:getCurrentSquare()
    local room = sq and sq:getRoom()
    local rname = room and try(room, "getName", nil)
    local town = KL.townAt and KL.townAt(player:getX(), player:getY()) or nil
    local where = player:getVehicle() and "in a vehicle" or (room and ("indoors" .. (rname and (" (" .. rname .. ")") or "")) or "outdoors")
    add("Where: " .. (town and (town .. ", ") or "") .. where .. ", floor " .. math.floor(player:getZ())
        .. ", map position " .. math.floor(player:getX()) .. "," .. math.floor(player:getY()) .. ".")
    if core.base and core.base.x then
        local dx, dy = core.base.x - player:getX(), core.base.y - player:getY()
        add("Base: " .. (core.base.n or "set") .. ", " .. math.floor(math.sqrt(dx * dx + dy * dy) + 0.5) .. " squares away.")
    end

    -- weather
    local wx = core.wx or {}
    local w = {}
    if wx.storm then table.insert(w, "thunderstorm") end
    if (wx.snow or 0) > 0.1 then table.insert(w, "snow") elseif (wx.rain or 0) > 0.1 then table.insert(w, "rain") end
    if (wx.fog or 0) > 0.4 then table.insert(w, "fog") end
    if wx.wind then table.insert(w, "windy") end
    if wx.t then table.insert(w, round(wx.t, 0) .. " C") end
    if #w > 0 then add("Weather: " .. join(w) .. ".") end
    local okW, power = pcall(function() return getWorld():isHydroPowerOn() end)
    if okW then add("The power grid is " .. (power and "still on" or "off") .. ".") end

    -- body
    local m = {}
    for _, md in ipairs(core.m or {}) do
        if md.n then table.insert(m, md.n .. (md.l and md.l > 1 and (" (level " .. md.l .. ")") or "")) end
    end
    add("Moodles: " .. (#m > 0 and join(m) or "none") .. ".")
    local body = collector("body") and collector("body")(player)
    local wounds = {}
    for _, part in ipairs(body and body.parts or {}) do
        if part.lines then
            local ls = {}
            for _, l in ipairs(part.lines) do table.insert(ls, l.t) end
            table.insert(wounds, part.n .. ": " .. join(ls))
        end
    end
    if #wounds > 0 then add("Wounds (as the health panel shows them): " .. join(wounds, "; ") .. ".") end
    if body and body.bandage then add("Best bandage on hand: " .. body.bandage .. ".") end

    -- things
    local hands = {}
    local p, s = player:getPrimaryHandItem(), player:getSecondaryHandItem()
    if p then table.insert(hands, heldText(p)) end
    if s and s ~= p then table.insert(hands, heldText(s)) end
    add("In hands: " .. (#hands > 0 and join(hands) or "nothing") .. ".")
    local wear = worn(player)
    if #wear > 0 then add("Wearing: " .. join(wear) .. ".") end
    local bars = core.bars or {}
    local c = carried(player)
    add("Carrying" .. (bars.weight and (" (weight " .. bars.weight .. " of " .. (bars.weightMax or "?") .. ")") or "") .. ": "
        .. (#c > 0 and join(c) or "nothing") .. ".")

    -- what's in reach
    local page = getPlayerLoot and getPlayerLoot(player:getPlayerNum()) or nil
    local near = {}
    if page and page.backpacks then
        for _, b in ipairs(page.backpacks) do
            local inv = b and b.inventory
            if inv and inv:getItems():size() > 0 then
                local names, seen = {}, {}
                local items = inv:getItems()
                for i = 0, math.min(items:size(), 24) - 1 do
                    local n = items:get(i):getDisplayName()
                    if not seen[n] then
                        seen[n] = true
                        table.insert(names, n)
                    end
                end
                table.insert(near, (b.name or "Container") .. ": " .. join(names))
            end
            if #near >= 8 then break end
        end
    end
    if #near > 0 then add("Within reach: " .. join(near, "; ") .. ".") end

    -- skills
    local skills = {}
    for i = 0, PerkFactory.PerkList:size() - 1 do
        local perk = PerkFactory.PerkList:get(i)
        if perk:getParent() ~= Perks.None then
            local lvl = player:getPerkLevel(perk:getType())
            if lvl > 0 then table.insert(skills, perk:getName() .. " " .. lvl) end
        end
    end
    if #skills > 0 then add("Skills: " .. join(skills) .. ".") end

    -- danger, by the game's own counts
    local stats = player:getStats()
    local seen, chasing = try(stats, "getNumVisibleZombies", 0), try(stats, "getNumChasingZombies", 0)
    if seen > 0 or chasing > 0 then
        add("Zombies: " .. seen .. " in sight" .. (chasing > 0 and (", " .. chasing .. " coming for the character") or "") .. ".")
    else
        add("No zombies in sight.")
    end

    -- the car
    local v = player:getVehicle()
    if v then
        local script = try(v, "getScript", nil)
        local sname = script and try(script, "getName", nil)
        local name = sname and (getTextOrNull("IGUI_VehicleName" .. sname) or sname) or "a vehicle"
        local fuel = core.veh and core.veh.fuel
        add("In " .. name .. (v:isDriver(player) and ", driving" or "") .. (fuel and (", fuel " .. math.floor(fuel * 100 + 0.5) .. "%") or "") .. ".")
    end
    return table.concat(L, "\n")
end

local last = nil

KL.addCollector("ask", 3000, function(player)
    local ok, text = pcall(situation, player)
    if not ok then
        KL.warn("ask", text)
        return nil
    end
    if text == last then return nil end
    last = text
    return { text = text, name = player:getDescriptor():getForename() }
end)
