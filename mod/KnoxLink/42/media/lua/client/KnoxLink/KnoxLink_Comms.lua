--[[
    Knox Link — what the character hears.

    radio: radio and TV broadcasts within earshot, using the game's own rule for when a
           broadcast reaches the player (close enough, same floor, not through an outside
           wall, not deaf). Kept in the save, so the log survives a reload.
    chat:  multiplayer chat, as the chat box shows it, and sending to it.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_State"

local KL = KnoxLink
local try = KL.try

local MAX_ENTRIES, MAX_LINES, MAX_CHAT = 40, 30, 120

KL.radioCount = KL.radioCount or 0
KL.chatCount = KL.chatCount or 0
local radioVersion, radioSent = 0, -1
local chat, chatVersion, chatSent = {}, 0, -1

--- When something was heard; the time only if the character could read it.
local function stamp(player)
    local gt = getGameTime()
    local hasClock = KL.clockOf(player)
    return {
        h = gt:getHour(), mi = gt:getMinutes(),
        w = (hasClock or not KL.immersive()) and true or nil,
        a = KL.round(gt:getWorldAgeHours(), 2),
    }
end

local function isDeaf(player)
    local ok, deaf = pcall(function() return player:hasTrait(CharacterTrait.DEAF) end)
    if ok and deaf ~= nil then return deaf == true end
    ok, deaf = pcall(function() return player:HasTrait("Deaf") end)
    return ok and deaf == true
end

-- ----------------------------------------------------------------------------- radio

local function radioLog(player)
    local md = player:getModData()
    if type(md.KnoxLinkRadio) ~= "table" then md.KnoxLinkRadio = {} end
    return md.KnoxLinkRadio
end

local function deviceInfo(device)
    local dd = device and try(device, "getDeviceData", nil)
    if not dd then return { k = "radio" } end
    local ch = try(dd, "getChannel", 0)
    local media = try(dd, "isPlayingMedia", false)
    local info = {
        k = media and "media" or (try(dd, "getIsTelevision", false) and "tv"
                or (try(dd, "getIsTwoWay", false) and "walkie" or "radio")),
        f = (not media) and ch or nil,
    }
    if media then
        local m = try(dd, "getMediaData", nil)
        info.s = m and (try(m, "getTranslatedTitle", nil) or try(m, "getTranslatedItemDisplayName", nil))
    else
        local zr = getZomboidRadio and getZomboidRadio()
        info.s = zr and try(zr, "getChannelName", nil, ch)
    end
    return info
end

--- The game's rule (ISRadioInteractions): a device on the player always, otherwise
--- close by, on the same floor and on the same side of the outside walls.
local function hears(player, x, y, z, device)
    if isDeaf(player) then return false end
    if x == -1 and y == -1 and z == -1 then return true end
    if math.floor(player:getZ()) ~= math.floor(z) then return false end
    local range = 5
    local dd = device and try(device, "getDeviceData", nil)
    if dd then range = math.min(12, math.max(range, try(dd, "getDeviceVolumeRange", 5) or 5)) end
    local dx, dy = player:getX() - x, player:getY() - y
    if dx * dx + dy * dy > range * range then return false end
    local sq = getCell():getGridSquare(math.floor(x), math.floor(y), math.floor(z))
    local psq = player:getCurrentSquare()
    if sq and psq and sq:isOutside() ~= psq:isOutside() then return false end
    return true
end

-- ----------------------------------------------------------------------------- forecast

--- Midnights since the world began (not shown as a date): which day a forecast was heard on.
function KL.dayIndex()
    local gt = getGameTime()
    return math.floor((gt:getWorldAgeHours() - gt:getTimeOfDay()) / 24 + 0.5)
end

--- One day of the game's forecast, the same values the Emergency Broadcast reads out.
local function forecastDay(fc)
    if not fc then return nil end
    local temp, cloud, wind = try(fc, "getTemperature", nil), try(fc, "getCloudiness", nil), try(fc, "getWindPower", nil)
    local d = {
        tmin = temp and KL.round(try(temp, "getTotalMin", 0), 1) or nil,
        tmax = temp and KL.round(try(temp, "getTotalMax", 0), 1) or nil,
        cloud = cloud and KL.round(try(cloud, "getTotalMean", 0), 2) or nil,
        cloudMax = cloud and KL.round(try(cloud, "getTotalMax", 0), 2) or nil,
        wind = wind and KL.round(try(wind, "getTotalMean", 0), 2) or nil,
        fog = try(fc, "isHasFog", false) or nil,
        heavy = try(fc, "isHasHeavyRain", false) or nil,
        storm = (try(fc, "isHasStorm", false) or try(fc, "isHasTropicalStorm", false)) or nil,
        blizzard = try(fc, "isHasBlizzard", false) or nil,
        snow = try(fc, "isChanceOnSnow", false) or nil,
    }
    if try(fc, "isWeatherStarts", false) then
        d.wx, d.at = "from", KL.round(try(fc, "getWeatherStartTime", 0), 0)
    elseif try(fc, "getWeatherOverlap", nil) then
        d.wx, d.at = "until", KL.round(try(fc, "getWeatherEndTime", 0), 0)
    end
    return d
end

--- The broadcast just read a forecast out loud: remember today's and tomorrow's.
local function rememberForecast(player, heardAt)
    local cm = getClimateManager()
    local f = cm and try(cm, "getClimateForecaster", nil)
    if not f then return end
    local days = {}
    for off = 0, 1 do
        local d = forecastDay(try(f, "getForecast", nil, off))
        if d then table.insert(days, d) end
    end
    if #days > 0 then player:getModData().KnoxLinkForecast = { di = KL.dayIndex(), t = heardAt, days = days } end
end

local forecastPrefixes = nil

local function isForecastLine(line)
    if not forecastPrefixes then
        forecastPrefixes = { getText("AEBS_Pre_today"), getText("AEBS_Pre_tomorrow") }
    end
    for _, p in ipairs(forecastPrefixes) do
        if p ~= "" and line:find(p, 1, true) == 1 then return true end
    end
    return false
end

local function isStatic(line)
    return line:match("^%s*<[%a%-%s]+>%s*$") ~= nil or line:match("^%s*$") ~= nil
end

-- a music note, made here: the game's Lua reader keeps only the low byte of characters written in a script
local NOTE = string.char(9834)

--- A broadcast line without the game's own markup: "[img=music]" is its music note, other [tags] set colours
--- or layout for its speech bubbles.
local function plain(line)
    local s = line:gsub("%[img=music%]", NOTE):gsub("%[/?%a+[^%]]*%]", "")
    return (s:gsub("%s%s+", " "):gsub("^%s+", ""):gsub("%s+$", ""))
end

local function onDeviceText(guid, codes, x, y, z, line, device)
    if type(line) ~= "string" or isStatic(line) then return end
    line = plain(line)
    if line == "" then return end
    local player = getPlayer()
    if not player or player:isDead() then return end
    if not hears(player, x, y, z, device) then return end
    local info = deviceInfo(device)
    local log = radioLog(player)
    local now = stamp(player)
    local last = log[#log]
    -- one card per broadcast: same station, heard within half an hour of the last line
    if last and last.k == info.k and last.f == info.f and last.s == info.s and last.t and now.a - (last.t.a or 0) < 0.5 then
        for i = math.max(1, #last.l - 3), #last.l do
            if last.l[i] == line then return end   -- the same line from a second set on that station
        end
        table.insert(last.l, line)
        if #last.l > MAX_LINES then table.remove(last.l, 1) end
        last.t2 = now
    else
        table.insert(log, { k = info.k, f = info.f, s = info.s, t = now, l = { line } })
        while #log > MAX_ENTRIES do table.remove(log, 1) end
    end
    KL.radioCount = KL.radioCount + 1
    player:getModData().KnoxLinkRadioCount = KL.radioCount
    radioVersion = radioVersion + 1
    if isForecastLine(line) then rememberForecast(player, now) end
end

Events.OnDeviceText.Add(function(...)
    local ok, err = pcall(onDeviceText, ...)
    if not ok then KL.warn("radio", err) end
end)

Events.OnGameStart.Add(function()
    local player = getPlayer()
    if player then KL.radioCount = tonumber(player:getModData().KnoxLinkRadioCount) or 0 end
    radioVersion = radioVersion + 1
end)

KL.addCollector("radio", 1000, function(player)
    if radioVersion == radioSent then return nil end
    radioSent = radioVersion
    local log = radioLog(player)
    -- newest first; lines kept by older versions may still carry the game's markup
    local out = {}
    for i = #log, 1, -1 do
        local e, c = log[i], {}
        for k, v in pairs(e) do c[k] = v end
        c.l = {}
        for j, l in ipairs(e.l or {}) do c.l[j] = plain(tostring(l)) end
        table.insert(out, c)
    end
    return { n = KL.radioCount, list = out }
end)

KL.addHandler("radioClear", function(player, a)
    player:getModData().KnoxLinkRadio = {}
    radioVersion = radioVersion + 1
    return true
end)

-- ----------------------------------------------------------------------------- chat

local function chatKind(message)
    local c = try(message, "getChat", nil)
    local t = c and try(c, "getType", nil)
    return t and tostring(t) or "general"
end

local function onAddMessage(message, tabID)
    if not message then return end
    local kind = chatKind(message)
    local author = try(message, "getAuthor", nil)
    -- broadcasts from radios already go to the radio log
    if kind == "radio" and (author == nil or author == "") then return end
    local text = try(message, "getText", "")
    if text == "" then return end
    local player = getPlayer()
    local entry = {
        k = kind,
        a = author,
        s = text,
        me = (player and author and author == player:getUsername()) or nil,
        ch = kind == "radio" and try(message, "getRadioChannel", nil) or nil,
        t = player and stamp(player) or nil,
        alert = try(message, "isServerAlert", false) or nil,
    }
    table.insert(chat, entry)
    while #chat > MAX_CHAT do table.remove(chat, 1) end
    KL.chatCount = KL.chatCount + 1
    chatVersion = chatVersion + 1
end

if Events.OnAddMessage then
    Events.OnAddMessage.Add(function(...)
        local ok, err = pcall(onAddMessage, ...)
        if not ok then KL.warn("chat", err) end
    end)
end

KL.addCollector("chat", 500, function(player)
    if chatVersion == chatSent then return nil end
    chatSent = chatVersion
    local out = {}
    for i = #chat, 1, -1 do table.insert(out, chat[i]) end
    local factions = false
    pcall(function() factions = Faction.getPlayerFaction(player) ~= nil end)
    local safehouse = false
    pcall(function() safehouse = SafeHouse.hasSafehouse(player) ~= nil end)
    return { n = KL.chatCount, mp = isClient() == true, list = out, can = { faction = factions, safehouse = safehouse } }
end)

local SENDERS = {
    say = function(s) processSayMessage(s) end,
    shout = function(s) processShoutMessage(s) end,
    general = function(s) processGeneralMessage(s) end,
    faction = function(s) proceedFactionMessage(s) end,
    safehouse = function(s) processSafehouseMessage(s) end,
}

KL.addHandler("chat", function(player, a)
    if not isClient() then return false, "Chat is for multiplayer games" end
    local text = tostring(a.s or ""):gsub("[\r\n]", " ")
    if text:match("^%s*$") then return false, "Nothing to send" end
    if #text > 200 then text = text:sub(1, 200) end
    if text:sub(1, 1) == "/" then return false, "Commands can only be typed in the game" end
    local send = SENDERS[tostring(a.ch or "say")]
    if not send then return false, "Unknown chat" end
    local ok, err = pcall(send, text)
    if not ok then
        KL.warn("chat send", err)
        return false, "The chat did not take it"
    end
    return true
end)
