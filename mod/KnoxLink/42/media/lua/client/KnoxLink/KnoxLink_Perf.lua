--[[
    Knox Link — how hard the device works: one-tap modes, and keeping it cool.

    Cool, Balanced and Smooth set the frame-rate cap (and, in Project Viewpoint's 3D view, how far
    its 3D furniture reaches); a rate picked by hand makes a custom mode. The heat control lowers
    the cap one step while the chip stays hot and raises it back, never above the mode's cap, once
    it has cooled down. It runs in the game, so it works with or without a screen connected; the
    choice is kept with the world.
]]

require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_Commands"

local KL = KnoxLink

local MODES = {
    cool = { cap = 30, ring = 1 },
    balanced = { cap = 60, ring = 2 },
    smooth = { cap = 90, ring = 3 },
}
local STEPS = { 30, 45, 60, 90, 120 }
-- the chip's hottest core in °C (a handheld's runs 60-75 in Viewpoint's 3D view, and slows itself near 95)
local HOT, COOL = 88, 76
local HOT_FOR, COOL_FOR = 20000, 60000   -- how long it has to stay there (ms)

local function settings()
    local md = ModData.getOrCreate("KnoxLinkPerf")
    if md.mode == nil then md.mode = "custom" end
    -- the heat control is on in new worlds; it only acts where the device reports its temperatures (handhelds)
    if md.auto == nil then md.auto = true end
    if md.rest == nil then md.rest = true end
    return md
end

local held = nil   -- the cap the heat control holds the game at now (nil: the mode's own)

local function modeCap(s)
    local m = MODES[s.mode]
    return m and m.cap or tonumber(s.cap) or 90
end

local function stepDown(cap)
    -- from the rate the game really reaches: a 90 cap means nothing to a 3D view running at 45
    local fps = getAverageFPS and tonumber(getAverageFPS()) or nil
    local from = fps and math.min(cap, fps + 3) or cap
    for i = #STEPS, 1, -1 do
        if STEPS[i] < from then return STEPS[i] end
    end
    return cap
end

local function stepUp(cap, most)
    for i = 1, #STEPS do
        if STEPS[i] > cap then return math.min(STEPS[i], most) end
    end
    return cap
end

-- Dead bodies in Project Viewpoint's 3D view. Its lighter presets draw every one as a flat picture; this
-- makes only the few nearest ones ahead of you real 3D models, cheaply: a short reach, a handful at most,
-- a couple posed a frame, no shadows. The pool for their poses (about 1.2 MiB each) goes first.
-- (measured on an AYN Thor in the 3D view with five bodies in sight: 42.9 fps flat, 42.4 with these)
local BODIES_NEAR = {
    { "memory.corpseMiB", "24" }, { "corpses.limit", "10" }, { "corpses.most", "8" }, { "corpses.band", "3" },
    { "corpses.inView", "true" }, { "corpses.every", "false" }, { "corpses.randomness", "0" },
    { "corpses.posesPerFrame", "2" }, { "corpses.shadows", "false" }, { "corpses.models", "true" },
}

--- 3D bodies near you on or off; false when Viewpoint isn't there to draw them.
function KL.applyBodies(on)
    if not (KnoxLink_viewpointSetting and KL.viewState and KL.viewState()) then return false end
    if not on then return KnoxLink_viewpointSetting("corpses.models", "false") end
    for _, kv in ipairs(BODIES_NEAR) do
        if not KnoxLink_viewpointSetting(kv[1], kv[2]) then return false end
    end
    return true
end

function KL.applyPerfMode(mode)
    local m = MODES[mode]
    if not m then return false end
    local s = settings()
    s.mode, held = mode, nil
    KL.setFrameCap(m.cap)
    if KnoxLink_viewpointSetting and KL.viewState and KL.viewState() then
        KnoxLink_viewpointSetting("lod.packRing", tostring(m.ring))
    end
    return true
end

--- A rate picked by hand: a custom mode, and the most the heat control goes back up to.
function KL.perfChosen(cap)
    local s = settings()
    s.mode, s.cap, held = "custom", cap, nil
end

--- For screens: { mode = "cool"|"balanced"|"smooth"|"custom", auto = bool, held = cap the heat control holds or nil,
--- bodies = 3D bodies near you (only with Viewpoint), rest = 30 fps while paused }.
function KL.perfState()
    local s = settings()
    local bodies = nil
    if KnoxLink_viewpointSetting and KL.viewState and KL.viewState() then bodies = s.bodies == true end
    return { mode = s.mode, auto = s.auto, held = held, bodies = bodies, rest = s.rest ~= false }
end

KL.addHandler("perfMode", function(player, a)
    if a.mode ~= nil and not KL.applyPerfMode(tostring(a.mode)) then return false, "Unknown mode" end
    if a.bodies ~= nil then
        if not KL.applyBodies(a.bodies == true) then return false, "Only in Project Viewpoint's 3D view" end
        settings().bodies = a.bodies == true
    end
    if a.rest ~= nil then settings().rest = a.rest == true end
    if a.auto ~= nil then
        settings().auto = a.auto == true
        if held then
            KL.setFrameCap(modeCap(settings()))   -- off: back to the mode's own rate
            held = nil
        end
    end
    KL.refresh("core")
    return true
end)

-- ----------------------------------------------------------------------------- resting while paused

-- A paused game draws the same still picture 45 to 90 times a second, and the device heats up and its fan
-- runs for nothing. A few seconds into a pause the frame rate drops to 30; it goes back the moment the game
-- runs again. The rate it was at is kept in a file too, so it comes back even if the game closes while paused.
local REST_CAP, REST_AFTER = 30, 3000
local REST_FILE = "KnoxLink_restcap.txt"
local pausedAt, restFrom, restCheckAt = nil, nil, 0

local function writeRest(cap)
    local w = getFileWriter and getFileWriter(REST_FILE, true, false)
    if not w then return end
    w:write(cap and tostring(cap) or "")
    w:close()
end

local function readRest()
    local r = getFileReader and getFileReader(REST_FILE, false)
    if not r then return nil end
    local line = r:readLine()
    r:close()
    return tonumber(line)
end

Events.OnTickEvenPaused.Add(function()
    local now = getTimestampMs()
    if now < restCheckAt then return end
    restCheckAt = now + 1000
    if isClient() then return end   -- multiplayer games don't pause
    local sc = UIManager.getSpeedControls and UIManager.getSpeedControls()
    local paused = sc ~= nil and sc:getCurrentGameSpeed() == 0
    if paused and settings().rest ~= false then
        pausedAt = pausedAt or now
        if not restFrom and now - pausedAt >= REST_AFTER then
            local cap = KL.frameCap()
            if cap and (cap == 0 or cap > REST_CAP) and KL.setFrameCap(REST_CAP) then
                restFrom = cap
                writeRest(cap)
            end
        end
    elseif not paused then
        pausedAt = nil
        if restFrom then
            local back = restFrom
            restFrom = nil
            writeRest(nil)
            -- (a rate picked meanwhile, a mode tapped during the pause, stays)
            if KL.frameCap() == REST_CAP then KL.setFrameCap(back) end
        end
    end
end)

-- ----------------------------------------------------------------------------- the heat control

local ema, hotSince, coolSince, nextAt, startAt = nil, nil, nil, 0, nil

Events.OnGameStart.Add(function()
    startAt = getTimestampMs() + 6000   -- the world's mode again, once Viewpoint has started
    ema, hotSince, coolSince, held = nil, nil, nil, nil
end)

Events.OnTick.Add(function()
    local now = getTimestampMs()
    if now < nextAt then return end
    nextAt = now + 2000
    local s = settings()
    if startAt and now >= startAt then
        startAt = nil
        -- closed while resting in a pause: the rate from before the pause first
        local left = readRest()
        if left then
            writeRest(nil)
            if KL.frameCap() == REST_CAP then KL.setFrameCap(left) end
        end
        if MODES[s.mode] then KL.applyPerfMode(s.mode) end
        if s.bodies ~= nil then KL.applyBodies(s.bodies) end   -- only once chosen: Viewpoint's own setting otherwise
    end
    if not s.auto or not KnoxLink_device then return end
    local ok, d = pcall(KnoxLink_device)
    local temp = ok and d and tonumber(d.cpu)
    if not temp then return end
    ema = ema and (ema * 0.8 + temp * 0.2) or temp   -- about the last ten seconds
    local most = modeCap(s)
    local cap = held or most
    if ema >= HOT then
        coolSince = nil
        hotSince = hotSince or now
        if now - hotSince >= HOT_FOR and cap > STEPS[1] then
            hotSince = now
            cap = stepDown(cap)
            if KL.setFrameCap(cap) then held = cap end
        end
    elseif ema <= COOL and cap < most then
        hotSince = nil
        coolSince = coolSince or now
        if now - coolSince >= COOL_FOR then
            coolSince = now
            cap = stepUp(cap, most)
            if KL.setFrameCap(cap) then held = (cap < most) and cap or nil end
        end
    else
        hotSince, coolSince = nil, nil
    end
end)
