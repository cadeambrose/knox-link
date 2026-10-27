--[[
    Knox Link — what a character leaves behind.

    When a character dies, their journal becomes a diary on their body: the game's own diary,
    in their words (readable in the game too, locked), which the next survivor can find and
    read on the second screen with its photos. The screens also get the run's recap: days
    survived, kills, how it ended, the big moments and, from the server add-on, the way they walked.

    Kept in the world's mod data (KnoxLinkLegacy[id], id = the journal's), so the diary outlives
    the character's own data; the item only carries the id.

      recap    (when a character dies): {id, name, first, prof, start = {y, mo, d}, died = {y, mo, d, h},
               hours, kills, cause, x, y, best = { {n, l}, ... }, moments = { {d, s}, ... }, ph = last photo id}
      diaries  (while the Journal tab is open): { list = { {id, name, days, on = true | near = true}, ... } }
               the left-behind diaries the character has on them or within reach (all of them, immersion off)
      diary    (once, on diaryRead {id, req}): {req, id, name, prof, traits, start, died, hours, cause,
               days = { {d, s = text, notes = {...}, ph = { {id, c}, ... }}, ... }}
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"
require "KnoxLink/KnoxLink_Journal"

local KL = KnoxLink
local try = KL.try

local PAGES = 40          -- Base.Diary1's PageToWrite
local PAGE_CHARS = 1400

local function legacy()
    return ModData.getOrCreate("KnoxLinkLegacy")
end

local function today()
    local gt = getGameTime()
    return { y = gt:getYear(), mo = gt:getMonth() + 1, d = gt:getDay() + 1, h = gt:getHour() }
end

local function causeOf(player)
    local by = try(player, "getAttackedBy", nil)
    if by and instanceof(by, "IsoZombie") then return "zombies" end
    local bd = player:getBodyDamage()
    if try(bd, "IsInfected", false) or try(bd, "isInfected", false) then return "the infection" end
    local stats = player:getStats()
    if CharacterStat then
        local ok, h = pcall(function() return stats:get(CharacterStat.HUNGER) end)
        if ok and type(h) == "number" and h >= 0.9 then return "starvation" end
        local ok2, t = pcall(function() return stats:get(CharacterStat.THIRST) end)
        if ok2 and type(t) == "number" and t >= 0.9 then return "thirst" end
    end
    return nil
end

--- The character's best skills (as the Skills tab reads them), strength and fitness aside.
local function bestSkills(player)
    local out = {}
    local list = PerkFactory and PerkFactory.PerkList
    if not list then return out end
    for i = 0, list:size() - 1 do
        local perk = list:get(i)
        local ok, skip = pcall(function()
            return perk:getParent() == Perks.None or perk:getType() == Perks.Strength or perk:getType() == Perks.Fitness
        end)
        if ok and not skip then
            local lvl = try(player, "getPerkLevel", 0, perk:getType())
            if lvl >= 2 then table.insert(out, { n = perk:getName(), l = lvl }) end
        end
    end
    table.sort(out, function(a, b) return a.l > b.l end)
    while #out > 3 do table.remove(out) end
    return out
end

--- The run's big moments, from the journal's lines: the places, the cars, the bites, the base.
local function moments(j)
    local out, places, cars = {}, 0, 0
    for _, p in ipairs(j.days) do
        for _, l in ipairs(p.lines or {}) do
            local keep = false
            if l.k == "place" and places < 3 then places, keep = places + 1, true
            elseif l.k == "car" and cars < 2 then cars, keep = cars + 1, true
            elseif l.k == "bite" or l.k == "base" then keep = true end
            if keep then table.insert(out, { d = p.d, s = tostring(l.s) }) end
        end
    end
    while #out > 7 do table.remove(out, 1) end
    return out
end

local function textOf(p)
    local s = p.ai or p.entry
    return type(s) == "string" and s ~= "" and s or nil
end

--- The game's diary item's pages: a first page with who it was, then a page a day.
local function itemPages(rec)
    local pages = {}
    local first = rec.name
    if rec.prof then first = first .. "\n" .. rec.prof end
    first = first .. "\n\n" .. (#rec.days == 1 and "One day." or ("Day 1 to day " .. rec.days[#rec.days].d .. "."))
    table.insert(pages, first)
    local days = {}
    for _, p in ipairs(rec.days) do
        if p.s or (p.notes and #p.notes > 0) then table.insert(days, p) end
    end
    -- too many days for the book: the first ones and the last ones
    if #days > PAGES - 1 then
        local keep = {}
        for i = 1, 5 do table.insert(keep, days[i]) end
        for i = #days - (PAGES - 1 - 5) + 1, #days do table.insert(keep, days[i]) end
        days = keep
    end
    for _, p in ipairs(days) do
        local s = "Day " .. p.d .. "\n\n" .. (p.s or "")
        for _, n in ipairs(p.notes or {}) do s = s .. "\n\n- " .. n end
        if #s > PAGE_CHARS then s = s:sub(1, PAGE_CHARS - 3) .. "..." end
        table.insert(pages, s)
    end
    return pages
end

local function record(player)
    local j, who, traits = KL.journalOf(player)
    local rec = {
        id = j.id, name = who.full ~= "" and who.full or "Someone", first = who.first, prof = who.jobLabel,
        traits = traits, start = j.start, died = today(), hours = KL.round(player:getHoursSurvived(), 1),
        kills = try(player, "getZombieKills", 0), cause = causeOf(player),
        x = math.floor(player:getX()), y = math.floor(player:getY()), days = {},
    }
    local lastPhoto = nil
    for _, p in ipairs(j.days) do
        local d = { d = p.d, s = textOf(p) }
        if p.notes and #p.notes > 0 then
            d.notes = {}
            for _, n in ipairs(p.notes) do table.insert(d.notes, tostring(n.s)) end
        end
        if p.ph and #p.ph > 0 then
            d.ph = {}
            for _, ph in ipairs(p.ph) do
                table.insert(d.ph, { id = ph.id, c = ph.c })
                lastPhoto = ph.id
            end
        end
        table.insert(rec.days, d)
    end
    rec.recap = {
        id = rec.id, name = rec.name, first = rec.first, prof = rec.prof, start = rec.start, died = rec.died,
        hours = rec.hours, kills = rec.kills, cause = rec.cause, x = rec.x, y = rec.y,
        best = bestSkills(player), moments = moments(j), ph = lastPhoto,
    }
    return rec
end

Events.OnPlayerDeath.Add(function(player)
    if not player or player ~= getPlayer() then return end
    local ok, err = pcall(function()
        local rec = record(player)
        legacy()[rec.id] = rec
        -- the diary, on the body
        local diary = player:getInventory():AddItem("Base.Diary1")
        if diary then
            local title = getText("UI_KnoxLink_DiaryOf", rec.first ~= "" and rec.first or rec.name)
            if title == "UI_KnoxLink_DiaryOf" then title = (rec.first ~= "" and rec.first or rec.name) .. "'s diary" end
            diary:setName(title)
            diary:setCustomName(true)
            for i, s in ipairs(itemPages(rec)) do
                pcall(function() diary:addPage(i, s) end)
            end
            pcall(function() diary:setLockedBy("KnoxLink") end)
            diary:getModData().KnoxLinkLegacy = rec.id
        end
        if KnoxLink_memorySave then KnoxLink_memorySave() end
        if KnoxLink_publish then KnoxLink_publish("recap", rec.recap) end
        print("[KnoxLink] " .. rec.name .. " died after " .. rec.hours .. " hours; their diary is on the body")
    end)
    if not ok then KL.warn("legacy", err) end
end)

-- ----------------------------------------------------------------------------- finding one

local function addFrom(items, out, seen, where)
    for i = 0, items:size() - 1 do
        local it = items:get(i)
        local t = it:getFullType()
        if t == "Base.Diary1" or t == "Base.Diary2" then
            local id = it:getModData().KnoxLinkLegacy
            if id and not seen[id] then
                seen[id] = where
                table.insert(out, id)
            end
        end
        if instanceof(it, "InventoryContainer") then addFrom(it:getItemContainer():getItems(), out, seen, where) end
    end
end

--- The left-behind diaries the character has on them ("on") or within reach ("near").
local function within(player)
    local ids, seen = {}, {}
    addFrom(player:getInventory():getItems(), ids, seen, "on")
    local page = getPlayerLoot and getPlayerLoot(player:getPlayerNum()) or nil
    if page and page.backpacks then
        for _, b in ipairs(page.backpacks) do
            if b and b.inventory then addFrom(b.inventory:getItems(), ids, seen, "near") end
        end
    end
    return ids, seen
end

local lastSig = nil

KL.addCollector("diaries", 2000, function(player)
    local ids, where = within(player)
    local all = legacy()
    if not KL.immersive() then
        for id, _ in pairs(all) do
            if not where[id] then
                where[id] = "known"
                table.insert(ids, id)
            end
        end
    end
    local list, sig = {}, ""
    for _, id in ipairs(ids) do
        local rec = all[id]
        if rec and id ~= KL.journalId(player) then
            local e = { id = id, name = rec.name, days = #(rec.days or {}), hours = rec.hours }
            e[where[id]] = true
            table.insert(list, e)
            sig = sig .. id .. where[id] .. ";"
        end
    end
    if sig == lastSig then return nil end
    lastSig = sig
    return { list = list }
end)

KL.addHandler("diaryRead", function(player, a)
    local id = tostring(a.id or "")
    local rec = legacy()[id]
    if not rec then return false, "That diary is gone" end
    if KL.immersive() then
        local _, where = within(player)
        if not where[id] then return false, "The diary isn't here anymore" end
        local ok, illiterate = pcall(function()
            if CharacterTrait and CharacterTrait.ILLITERATE then return player:hasTrait(CharacterTrait.ILLITERATE) end
            return player:HasTrait("Illiterate")
        end)
        if ok and illiterate then return false, "Your character can't read" end
    end
    if KnoxLink_publish then
        KnoxLink_publish("diary", {
            req = tonumber(a.req) or 0, id = rec.id, name = rec.name, prof = rec.prof, traits = rec.traits,
            start = rec.start, died = rec.died, hours = rec.hours, cause = rec.cause, days = rec.days,
        })
    end
    return true
end)
