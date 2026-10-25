--[[
    Knox Link — the in-game side: a connection panel (address, PIN, QR code), mod options
    and a short hint when a game starts.
]]

require "ISUI/ISCollapsableWindow"
require "KnoxLink/KnoxLink_Core"
require "KnoxLink/KnoxLink_Commands"

local KL = KnoxLink

local function T(key, fallback)
    local t = getTextOrNull(key)
    return t or fallback
end

--- A translated line with one value in it. The game fills in %1 itself; asking for such a line
--- without the value (getTextOrNull) logs a warning.
local function T1(key, fallback, value)
    local t = getText(key, value)
    if t == key then return (fallback:gsub("%%1", tostring(value))) end
    return t
end

-- ----------------------------------------------------------------------------- options

if PZAPI and PZAPI.ModOptions then
    local o = PZAPI.ModOptions:create("KnoxLink", "Knox Link")
    KL.options = {
        key = o:addKeyBind("panelKey", "UI_KnoxLink_OptPanelKey", Keyboard.KEY_F8, "UI_KnoxLink_OptPanelKey_tip"),
        immersion = o:addTickBox("immersion", "UI_KnoxLink_OptImmersion", true, "UI_KnoxLink_OptImmersion_tip"),
        lan = o:addTickBox("lan", "UI_KnoxLink_OptLan", true, "UI_KnoxLink_OptLan_tip"),
        arrow = o:addTickBox("arrow", "UI_KnoxLink_OptArrow", true, "UI_KnoxLink_OptArrow_tip"),
        hint = o:addTickBox("hint", "UI_KnoxLink_OptHint", true, "UI_KnoxLink_OptHint_tip"),
    }
    -- shortcuts a handheld's spare buttons can be mapped to (none set: they'd clash with someone's keys)
    if o.addTitle then o:addTitle("UI_KnoxLink_OptShortcuts") end
    KL.shortcuts = {
        { opt = o:addKeyBind("keyView", "UI_KnoxLink_OptKeyView", 0, "UI_KnoxLink_OptKeyView_tip"), cmd = "view" },
        { opt = o:addKeyBind("keyBandage", "UI_KnoxLink_OptKeyBandage", 0, "UI_KnoxLink_OptKeyBandage_tip"), cmd = "quick", a = "bandage" },
        { opt = o:addKeyBind("keyEat", "UI_KnoxLink_OptKeyEat", 0, "UI_KnoxLink_OptKeyEat_tip"), cmd = "quick", a = "eat" },
        { opt = o:addKeyBind("keyDrink", "UI_KnoxLink_OptKeyDrink", 0, "UI_KnoxLink_OptKeyDrink_tip"), cmd = "quick", a = "drink" },
        { opt = o:addKeyBind("keyLight", "UI_KnoxLink_OptKeyLight", 0, "UI_KnoxLink_OptKeyLight_tip"), cmd = "quick", a = "light" },
        { opt = o:addKeyBind("keyWeapon", "UI_KnoxLink_OptKeyWeapon", 0, "UI_KnoxLink_OptKeyWeapon_tip"), cmd = "quick", a = "weapon" },
    }
    o.apply = function()
        if KL.hasServer() then KnoxLink_setLan(KL.options.lan:getValue()) end
        local player = getPlayer()
        if player then KL.setWaypoint(player, KL.waypoint) end
    end
    Events.OnMainMenuEnter.Add(o.apply)
    Events.OnGameStart.Add(o.apply)
end

local function optionOn(name, default)
    local opt = KL.options and KL.options[name]
    if not opt then return default end
    return opt:getValue()
end

-- ----------------------------------------------------------------------------- panel

KnoxLinkPanel = ISCollapsableWindow:derive("KnoxLinkPanel")

local FONT_BIG = UIFont.Large
local FONT = UIFont.Medium
local FONT_SMALL = UIFont.Small
local ACCENT = { r = 0.95, g = 0.66, b = 0.23 }

function KnoxLinkPanel:new(x, y)
    local w, h = 460, 560
    local o = ISCollapsableWindow.new(self, x, y, w, h)
    o.title = "Knox Link"
    o.resizable = false
    o.qrFor = nil
    o.qr = nil
    o:setResizable(false)
    return o
end

function KnoxLinkPanel:createChildren()
    ISCollapsableWindow.createChildren(self)
    local th = self:titleBarHeight()
    local bw, bh = 130, 28
    self.btnPin = ISButton:new(16, self.height - bh - 14, bw, bh, T("UI_KnoxLink_NewPin", "New PIN"), self, KnoxLinkPanel.onNewPin)
    self.btnPin:initialise()
    self:addChild(self.btnPin)
    self.btnClose = ISButton:new(self.width - bw - 16, self.height - bh - 14, bw, bh, T("UI_KnoxLink_Close", "Close"), self, KnoxLinkPanel.close)
    self.btnClose:initialise()
    self:addChild(self.btnClose)
    self.top = th + 12
end

function KnoxLinkPanel:onNewPin()
    if KL.hasServer() then KnoxLink_newPin() end
    self.qrFor = nil
end

function KnoxLinkPanel:close()
    self:setVisible(false)
    self:removeFromUIManager()
    KnoxLinkPanel.instance = nil
end

local function drawCentered(self, text, y, font, r, g, b, a)
    local w = getTextManager():MeasureStringX(font, text)
    self:drawText(text, (self.width - w) / 2, y, r, g, b, a or 1, font)
    return y + getTextManager():getFontHeight(font)
end

function KnoxLinkPanel:prerender()
    ISCollapsableWindow.prerender(self)
end

function KnoxLinkPanel:render()
    ISCollapsableWindow.render(self)
    if self.isCollapsed then return end
    local y = self.top
    local fh = getTextManager():getFontHeight(FONT)

    if not KL.hasServer() then
        y = drawCentered(self, T("UI_KnoxLink_NoServer1", "The Knox Link Server add-on is not running."), y + 10, FONT, 1, 0.8, 0.5)
        y = y + 8
        local lines = {
            T("UI_KnoxLink_NoServer2", "Enable \"Knox Link Server\" in the mod list."),
            T("UI_KnoxLink_NoServer3", "It needs the ZombieBuddy framework installed."),
            T("UI_KnoxLink_NoServer4", "See the mod page for a 2-minute guide."),
        }
        for _, l in ipairs(lines) do y = drawCentered(self, l, y, FONT_SMALL, 0.85, 0.85, 0.85) + 4 end
        return
    end

    local st = KnoxLink_status()
    if not st or not st.running then
        drawCentered(self, T("UI_KnoxLink_NotRunning", "The server could not start."), y + 10, FONT, 1, 0.5, 0.4)
        if st and st.error and st.error ~= "" then drawCentered(self, st.error, y + 40, FONT_SMALL, 0.8, 0.8, 0.8) end
        return
    end

    -- status line
    local screens = st.screens or 0
    local status = screens == 0 and T("UI_KnoxLink_Waiting", "Ready - no screen connected yet")
        or (screens == 1 and T("UI_KnoxLink_OneScreen", "1 screen connected"))
        or T1("UI_KnoxLink_Screens", "%1 screens connected", screens)
    local dotR, dotG, dotB = 0.35, 0.85, 0.45
    if screens == 0 then dotR, dotG, dotB = ACCENT.r, ACCENT.g, ACCENT.b end
    local sw = getTextManager():MeasureStringX(FONT, status) + 18
    local sx = (self.width - sw) / 2
    self:drawRect(sx, y + fh / 2 - 4, 8, 8, 1, dotR, dotG, dotB)
    self:drawText(status, sx + 18, y, 0.92, 0.92, 0.92, 1, FONT)
    y = y + fh + 10

    -- first address is the one to type / scan
    local urls = st.urls or {}
    local main = urls[1] or ""
    local pin = st.pin or ""
    local qrText = main
    if st.lan and main ~= "" and not string.find(main, "127.0.0.1", 1, true) then qrText = main .. "/#pin=" .. pin end

    if self.qrFor ~= qrText then
        self.qrFor = qrText
        self.qr = nil
        local rows = KnoxLink_qr(qrText)
        if rows then
            self.qr = {}
            for i = 1, #rows do self.qr[i] = rows[i] end
        end
    end

    if self.qr and #self.qr > 0 then
        local n = #self.qr
        local size = 230
        local cell = math.floor(size / n)
        local q = cell * n
        local pad = cell * 3
        local qx = math.floor((self.width - q) / 2)
        self:drawRect(qx - pad, y - pad + pad, q + pad * 2, q + pad * 2, 1, 1, 1, 1)
        local oy = y + pad
        for r = 1, n do
            local row = self.qr[r]
            local c = 1
            while c <= n do
                if string.byte(row, c) == 49 then   -- "1": draw the whole run in one rect
                    local start = c
                    while c <= n and string.byte(row, c) == 49 do c = c + 1 end
                    self:drawRect(qx + (start - 1) * cell, oy + (r - 1) * cell, (c - start) * cell, cell, 1, 0, 0, 0)
                else
                    c = c + 1
                end
            end
        end
        y = y + q + pad * 2 + 12
    end

    y = drawCentered(self, main, y, FONT_BIG, ACCENT.r, ACCENT.g, ACCENT.b) + 4
    if st.lan then
        y = drawCentered(self, T1("UI_KnoxLink_Pin", "PIN  %1", pin), y, FONT_BIG, 1, 1, 1) + 8
    end
    y = drawCentered(self, T("UI_KnoxLink_Help1", "Open the address in a browser on your phone, tablet,"), y, FONT_SMALL, 0.75, 0.75, 0.75)
    y = drawCentered(self, T("UI_KnoxLink_Help2", "second monitor or dual-screen handheld - same Wi-Fi."), y, FONT_SMALL, 0.75, 0.75, 0.75) + 6
    for i = 2, #urls do
        y = drawCentered(self, urls[i], y, FONT_SMALL, 0.6, 0.6, 0.6)
    end
end

function KnoxLinkPanel.toggle()
    if KnoxLinkPanel.instance then
        KnoxLinkPanel.instance:close()
        return
    end
    local core = getCore()
    local x = (core:getScreenWidth() - 460) / 2
    local y = (core:getScreenHeight() - 560) / 2
    local p = KnoxLinkPanel:new(x, y)
    p:initialise()
    p:addToUIManager()
    KnoxLinkPanel.instance = p
end

Events.OnKeyPressed.Add(function(key)
    local player = getPlayer()
    if not player then return end
    local opt = KL.options and KL.options.key
    local bound = opt and opt:getValue() or Keyboard.KEY_F8
    if key == bound and bound ~= 0 then KnoxLinkPanel.toggle() end
    -- the shortcuts run the same handlers as Knox Link's buttons; a refusal shows as a line over the character
    for _, s in ipairs(KL.shortcuts or {}) do
        local k = s.opt:getValue()
        if k ~= 0 and key == k and KL.handlers[s.cmd] then
            local ok, r1, r2 = pcall(KL.handlers[s.cmd], player, { a = s.a })
            if ok and r1 == false and r2 then HaloTextHelper.addBadText(player, tostring(r2)) end
        end
    end
end)

-- ----------------------------------------------------------------------------- start-up hint

KnoxLinkHint = ISUIElement:derive("KnoxLinkHint")

function KnoxLinkHint:new(text, sub)
    local core = getCore()
    local w = math.max(getTextManager():MeasureStringX(FONT, text), getTextManager():MeasureStringX(FONT_SMALL, sub)) + 48
    local h = getTextManager():getFontHeight(FONT) + getTextManager():getFontHeight(FONT_SMALL) + 26
    local o = ISUIElement.new(self, (core:getScreenWidth() - w) / 2, 70, w, h)
    o.text, o.sub = text, sub
    o.born = getTimestampMs()
    return o
end

function KnoxLinkHint:render()
    local age = getTimestampMs() - self.born
    local life = 14000
    if age > life or (KL.hasServer() and KnoxLink_screens() > 0 and age > 1500) then
        self:setVisible(false)
        self:removeFromUIManager()
        return
    end
    local a = 1
    if age < 300 then a = age / 300 elseif age > life - 600 then a = (life - age) / 600 end
    self:drawRect(0, 0, self.width, self.height, 0.82 * a, 0.06, 0.07, 0.08)
    self:drawRect(0, 0, 4, self.height, a, ACCENT.r, ACCENT.g, ACCENT.b)
    self:drawText(self.text, 24, 10, 1, 1, 1, a, FONT)
    self:drawText(self.sub, 24, 12 + getTextManager():getFontHeight(FONT), 0.78, 0.78, 0.78, a, FONT_SMALL)
end

local function showHint()
    if not optionOn("hint", true) or not KL.hasServer() then return end
    local st = KnoxLink_status()
    if not st or not st.running or (st.screens or 0) > 0 then return end
    local main = (st.urls and st.urls[1]) or ""
    local text = T1("UI_KnoxLink_HintTitle", "Knox Link: open %1 on your second screen", main)
    if st.lan then text = text .. "  -  PIN " .. tostring(st.pin) end
    local keyName = Keyboard.getKeyName(KL.options and KL.options.key and KL.options.key:getValue() or Keyboard.KEY_F8) or "F8"
    local sub = T1("UI_KnoxLink_HintSub", "Press %1 for a QR code and more options.", keyName)
    local h = KnoxLinkHint:new(text, sub)
    h:initialise()
    h:addToUIManager()
end

local hintAt = nil
Events.OnGameStart.Add(function() hintAt = getTimestampMs() + 4000 end)
Events.OnTickEvenPaused.Add(function()
    if hintAt and getTimestampMs() >= hintAt then
        hintAt = nil
        showHint()
    end
end)
