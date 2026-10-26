--[[
    Knox Link — second screen companion for Project Zomboid.

    Core: a small scheduler that gathers game state at a steady pace and hands it to the
    transport, and a command dispatcher for what screens ask the game to do.

    Transport: the optional "Knox Link Server" add-on (a ZombieBuddy Java mod) defines the
    global KnoxLink_* functions and runs the web server. Without it, Knox Link shows how to
    enable it and does no work at all.
]]

KnoxLink = KnoxLink or {}
local KL = KnoxLink

KL.VERSION = "1.1.0"
KL.collectors = KL.collectors or {}
KL.handlers = KL.handlers or {}

local warned = {}

function KL.warn(what, err)
    -- One line per distinct problem, so a broken collector cannot flood console.txt.
    local key = tostring(what) .. tostring(err)
    if warned[key] then return end
    warned[key] = true
    print("[KnoxLink] " .. tostring(what) .. ": " .. tostring(err))
end

function KL.hasServer()
    return KnoxLink_isServer ~= nil
end

--- Registers a state collector.
-- channel: what screens subscribe to ("core" always goes out)
-- everyMs: how often it runs while someone is watching
-- fn(player): returns a table to publish, or nil to skip this round
function KL.addCollector(channel, everyMs, fn)
    for _, c in ipairs(KL.collectors) do
        if c.channel == channel then
            c.every, c.fn = everyMs, fn
            return
        end
    end
    table.insert(KL.collectors, { channel = channel, every = everyMs, fn = fn, nextAt = 0 })
end

--- Registers a command handler: fn(player, args) -> ok, message
function KL.addHandler(name, fn)
    KL.handlers[name] = fn
end

--- Re-run a channel on the next tick (after a command changed what it shows).
function KL.refresh(channel)
    for _, c in ipairs(KL.collectors) do
        if channel == nil or c.channel == channel then c.nextAt = 0 end
    end
end

local function runCommand(c)
    local player = getPlayer()
    local handler = KL.handlers[c.cmd]
    local ok, msg
    if not player then
        ok, msg = false, "Not in game"
    elseif not handler then
        ok, msg = false, "Unknown command " .. tostring(c.cmd)
    else
        local success, r1, r2 = pcall(handler, player, c.args or {})
        if success then
            ok, msg = r1 ~= false, r2
        else
            ok, msg = false, "Something went wrong"
            KL.warn("command " .. tostring(c.cmd), r1)
        end
    end
    KnoxLink_reply(c.id, ok, msg and tostring(msg) or "")
    KL.refresh()
end

local nextPollAt = 0

local function onTick()
    if not KL.hasServer() then return end
    local now = getTimestampMs()

    if now >= nextPollAt then
        nextPollAt = now + 50
        for _ = 1, 8 do
            local c = KnoxLink_pollCommand()
            if not c then break end
            runCommand(c)
        end
    end

    if KnoxLink_screens() < 1 then return end
    local player = getPlayer()
    if not player then return end

    for _, c in ipairs(KL.collectors) do
        if now >= c.nextAt and (c.channel == "core" or KnoxLink_wants(c.channel)) then
            c.nextAt = now + c.every
            local ok, data = pcall(c.fn, player)
            if not ok then
                KL.warn("collector " .. c.channel, data)
            elseif data ~= nil then
                KnoxLink_publish(c.channel, data)
            end
        end
    end
end

Events.OnTickEvenPaused.Add(onTick)

Events.OnGameStart.Add(function()
    print("[KnoxLink] " .. KL.VERSION .. " ready, server add-on " .. (KL.hasServer() and "found" or "NOT found"))
end)

-- ---------------------------------------------------------------------------- helpers

function KL.round(v, places)
    if type(v) ~= "number" then return 0 end
    local m = 10 ^ (places or 0)
    return math.floor(v * m + 0.5) / m
end

function KL.clamp01(v)
    if type(v) ~= "number" then return 0 end
    if v < 0 then return 0 end
    if v > 1 then return 1 end
    return v
end

--- Calls obj:method(...) and returns the result, or the fallback if the method is gone.
function KL.try(obj, method, fallback, ...)
    if obj == nil then return fallback end
    local f = obj[method]
    if type(f) ~= "function" then return fallback end
    local ok, r = pcall(f, obj, ...)
    if ok and r ~= nil then return r end
    return fallback
end

--- Finds an item the player carries (main inventory, bags, worn and held items) by id.
function KL.findItem(player, id)
    id = tonumber(id)
    if not id then return nil end
    local inv = player:getInventory()
    local item = inv:getItemWithIDRecursiv(id)
    if item then return item end
    local held = { player:getPrimaryHandItem(), player:getSecondaryHandItem() }
    for _, h in ipairs(held) do
        if h and h:getID() == id then return h end
    end
    return nil
end
