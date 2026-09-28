package dev.peashoot.app.farm

/** What the first plot beyond the starting island costs. */
private const val FIRST_PLOT = 20

/** How much dearer each plot is than the one bought before it. */
private const val PLOT_STEP = 15

/** What the next plot costs when [owned] plots are already bought. */
fun plotPrice(owned: Int): Int = FIRST_PLOT + PLOT_STEP * owned

/**
 * The farm with the next plot bought, or the farm as it was when the purse cannot pay for it. Only
 * a person's click leads here, like [dismissed]: the window asks and the farm decides, so a click
 * the purse cannot cover changes nothing rather than going into debt.
 */
fun FarmState.boughtPlot(): FarmState {
    val price = plotPrice(land)
    return if (purse.coins < price) this
    else copy(purse = purse.copy(coins = purse.coins - price), land = land + 1)
}
