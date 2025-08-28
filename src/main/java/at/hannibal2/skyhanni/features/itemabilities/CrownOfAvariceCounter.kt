package at.hannibal2.skyhanni.features.itemabilities

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.core.config.Position
import at.hannibal2.skyhanni.config.features.itemability.CrownOfAvariceConfig.CrownOfAvariceLines
import at.hannibal2.skyhanni.events.IslandChangeEvent
import at.hannibal2.skyhanni.events.OwnInventoryItemUpdateEvent
import at.hannibal2.skyhanni.events.SecondPassedEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.InventoryUtils
import at.hannibal2.skyhanni.utils.ItemUtils.getInternalNameOrNull
import at.hannibal2.skyhanni.utils.NeuInternalName.Companion.toInternalName
import at.hannibal2.skyhanni.utils.NeuItems.getItemStack
import at.hannibal2.skyhanni.utils.NumberUtil.addSeparators
import at.hannibal2.skyhanni.utils.NumberUtil.billion
import at.hannibal2.skyhanni.utils.NumberUtil.shortFormat
import at.hannibal2.skyhanni.utils.RecalculatingValue
import at.hannibal2.skyhanni.utils.RenderDisplayHelper
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderables
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.SkyBlockItemModifierUtils.getCoinsOfAvarice
import at.hannibal2.skyhanni.utils.SkyBlockUtils
import at.hannibal2.skyhanni.utils.Stopwatch
import at.hannibal2.skyhanni.utils.TimeUtils.format
import at.hannibal2.skyhanni.utils.collection.RenderableCollectionUtils.addHorizontalSpacer
import at.hannibal2.skyhanni.utils.collection.RenderableCollectionUtils.addItemStack
import at.hannibal2.skyhanni.utils.collection.RenderableCollectionUtils.addString
import at.hannibal2.skyhanni.utils.inPartialHours
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.renderables.addLine
import at.hannibal2.skyhanni.utils.renderables.container.HorizontalContainerRenderable.Companion.horizontal
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.client.gui.inventory.GuiInventory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@SkyHanniModule
object CrownOfAvariceCounter {

    private val config get() = SkyHanniMod.feature.inventory.itemAbilities.crownOfAvarice

    private val internalName = "CROWN_OF_AVARICE".toInternalName()

    private var display: List<Renderable> = emptyList()
    private const val MAX_AVARICE_COINS: Int = 1_000_000_000
    private var inventoryOpen = false
    private val isWearingCrown by RecalculatingValue(1.seconds) {
        InventoryUtils.getHelmet()?.getInternalNameOrNull() == internalName
    }

    private var count: Long? = null
    private var coinsEarned: Long = 0L
    private var sessionUptime: Stopwatch = Stopwatch()
    private val isSessionActive get(): Boolean = sessionUptime.getDuration() < config.sessionActiveTime.seconds
    private var coinsDifference: Long? = null

    init {
        RenderDisplayHelper(
            outsideInventory = true,
            inOwnInventory = true,
            condition = { isEnabled() && isWearingCrown },
            onRender = { renderDisplay(config.position) }
        )
    }

    fun renderDisplay(pos: Position) {
        val invCurrentlyOpen = Minecraft.getMinecraft().currentScreen?.let { it is GuiInventory || it is GuiChest } ?: false
        if (inventoryOpen != invCurrentlyOpen) {
            inventoryOpen = invCurrentlyOpen
            update()
        }

        pos.renderRenderables(display, posLabel = "Crown of Avarice Counter")
    }


    @HandleEvent
    fun onSecondPassed(event: SecondPassedEvent) {
        if (!isEnabled()) return
        if (!isWearingCrown) return
        update()
    }

    @HandleEvent
    fun onInventoryUpdated(event: OwnInventoryItemUpdateEvent) {
        if (!isEnabled() || event.slot != 5) return
        val item = event.itemStack
        if (item.getInternalNameOrNull() != internalName) return
        val coins = item.getCoinsOfAvarice() ?: return
        if (count == null) count = coins
        coinsDifference = coins - (count ?: 0)

        if (coinsDifference == 0L) return

        if ((coinsDifference ?: 0) < 0) {
            reset()
            count = coins
            return
        }

        sessionUptime.start()
        sessionUptime.lap()
        coinsEarned += coinsDifference ?: 0
        count = coins

        update()
    }

    @HandleEvent
    fun onIslandChange(event: IslandChangeEvent) {
        if (config.resetOnWorldChange) reset()
        count = InventoryUtils.getHelmet()?.getCoinsOfAvarice()
    }

    private fun update() {
        //No need to update if paused, we'll unpause with onInventoryUpdated
        if (sessionUptime.isPaused()) return

        if (sessionUptime.getLapTime()?.let{it > config.afkTimeout.seconds} != false) {
            sessionUptime.pause(true)
        }
        display = buildDisplay()
    }

    private fun fmtDisplay(lines: MutableMap<CrownOfAvariceLines, Renderable>): List<Renderable> {
        val newList = mutableListOf<Renderable>()
        newList.addLine {
            addItemStack(internalName.getItemStack())
            addString("§6" + if (config.shortFormat) count?.shortFormat() else count?.addSeparators())
        }
        newList.addAll(config.text.mapNotNull { lines[it] })

        if (inventoryOpen) {
            newList.addLine {
                add(Renderable.clickable(text = "§c[Reset session]", onLeftClick = ::reset))
                addHorizontalSpacer(3)
                add(Renderable.clickable(text = "§6[Pause session]", onLeftClick = ::pauseSession))
            }
        }
        return newList
    }

    private fun buildDisplay(): List<Renderable> {

        val lines = mutableMapOf<CrownOfAvariceLines, Renderable>()
        lines[CrownOfAvariceLines.COINSPERHOUR] = Renderable.horizontal {
            val coinsPerHour = calculateCoinsPerHour().toLong()
            addString(
                "§aCoins Per Hour: §6${
                    if (isSessionActive) "Calculating..."
                    else if (config.shortFormatCPH) coinsPerHour.shortFormat() else coinsPerHour.addSeparators()
                } " + if (sessionUptime.isPaused()) "§c(PAUSED)" else "",
            )
        }
        lines[CrownOfAvariceLines.TIMEUNTILMAX] = Renderable.horizontal {
            val timeUntilMax = calculateTimeUntilMax()
            addString(
                "§aTime until Max: §6${if (isSessionActive) "Calculating..." else timeUntilMax} " +
                    if (sessionUptime.isPaused()) "§c(PAUSED)" else "",
            )
        }

        lines[CrownOfAvariceLines.COINDIFFERENCE] = Renderable.horizontal {
            addString("§aLast coins gained: §6$coinsDifference")
        }

        lines[CrownOfAvariceLines.SESSIONCOINS] = Renderable.horizontal {
            addString("§aCoins this session: §6${coinsEarned.addSeparators()}")
        }

        lines[CrownOfAvariceLines.SESSIONTIME] = Renderable.horizontal {
            addString("§aSession Time: §6${sessionUptime.getDuration().format()}")
        }

        return fmtDisplay(lines)
    }


    private fun isEnabled() = SkyBlockUtils.inSkyBlock && config.enable

    private fun reset() {
        coinsEarned = 0L
        sessionUptime = Stopwatch()
        coinsDifference = 0L
    }

    private fun pauseSession() {
        sessionUptime.pause()
    }

    private fun calculateCoinsPerHour(): Double {
        val timeInHours = sessionUptime.getDuration().inPartialHours
        return if (timeInHours > 0) coinsEarned / timeInHours else 0.0
    }

    //private fun isSessionAFK() = sessionUptime.getLapTime()?.let {it > maxAfkTime.seconds} ?: true

    private fun calculateTimeUntilMax(): String {
        val coinsPerHour = calculateCoinsPerHour()
        if (coinsPerHour == 0.0) return "Forever..."
        val timeUntilMax = ((MAX_AVARICE_COINS - (count ?: 0)) / coinsPerHour).hours
        return timeUntilMax.format()
    }

}
