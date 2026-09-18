package com.bncam.data.settings

enum class NoiseModelPresetGroup(val sectionTitle: String) {
    MAIN("Main camera"),
    ULTRA_WIDE("Ultra-wide"),
    TELE("Telephoto"),
    FRONT("Front camera")
}

/**
 * Twenty BnCam-native parametric noise presets. Each lens class uses a robust four-channel base
 * derived from a broad statistical sensor-model reference pool and five monotonic variance scales. They are intended as
 * physically plausible starting points when exact sensor calibration is unavailable; OEM/System
 * remains preferable when trustworthy metadata exists.
 */
object BnCamNoiseModelPresets {
    private data class Tier(val suffix: String, val scale: Double, val description: String)

    private val tiersMain = listOf(
        Tier("Low noise", 0.65, "Large/efficient main-sensor starting point with restrained variance."),
        Tier("Low-medium", 0.82, "Clean main-sensor model with modest read and shot noise."),
        Tier("Balanced", 1.00, "General-purpose main-camera physical noise model."),
        Tier("Medium-high", 1.25, "Denser-pixel or less efficient main-sensor starting point."),
        Tier("High noise", 1.55, "High-noise main-camera model for difficult small-pixel/high-gain behavior.")
    )
    private val tiersUltra = listOf(
        Tier("Low noise", 0.65, "Large/efficient ultra-wide starting point."),
        Tier("Low-medium", 0.85, "Clean ultra-wide model with moderate channel asymmetry."),
        Tier("Balanced", 1.08, "General-purpose ultra-wide physical noise model."),
        Tier("Medium-high", 1.38, "Higher-noise ultra-wide model for smaller sensors."),
        Tier("High noise", 1.75, "Strong ultra-wide noise model for compact/high-gain modules.")
    )
    private val tiersTele = listOf(
        Tier("Low noise", 0.60, "Clean telephoto starting point for larger or bright tele modules."),
        Tier("Low-medium", 0.80, "Moderate telephoto variance model."),
        Tier("Balanced", 1.00, "General-purpose telephoto/periscope physical noise model."),
        Tier("Medium-high", 1.30, "Higher-noise telephoto model for slower optics or denser sensors."),
        Tier("High noise", 1.65, "Strong tele/periscope model for low-light and high-gain operation.")
    )
    private val tiersFront = listOf(
        Tier("Low noise", 0.70, "Clean front-camera starting point for larger modern selfie sensors."),
        Tier("Low-medium", 0.90, "Low-to-moderate front-camera variance model."),
        Tier("Balanced", 1.12, "General-purpose front-camera physical noise model."),
        Tier("Medium-high", 1.40, "Higher-noise front-camera model for small pixels."),
        Tier("High noise", 1.75, "Strong front-camera model for compact/high-resolution modules.")
    )

    private val mainBase = PersistedParametricNoiseModel(
        a=listOf(6.583810665079091e-7,6.458050664376449e-7,6.45747862695993e-7,6.458083677325721e-7),
        b=listOf(2.3530087028804287e-6,1.9309687469984246e-6,1.8463541034306045e-6,2.1574028292878407e-6),
        c=listOf(9.966540549262827e-13,6.422416817927681e-13,7.084057732781313e-13,9.816312547566335e-13),
        d=listOf(4.724485760633276e-7,4.783970529300197e-7,4.704595801385923e-7,4.694918175448546e-7),
        isoStep=3200.0)
    private val ultraBase = PersistedParametricNoiseModel(
        a=listOf(1.1541073760030817e-6,1.2351069583180527e-6,6.329863259007093e-7,6.990107261921658e-7),
        b=listOf(3.934197268764304e-6,1.3242422238397967e-5,1.3363485739046744e-5,7.85098323736981e-6),
        c=listOf(3.3264582037381913e-12,2.6164366774202063e-12,1.2934486313498188e-12,1.4641034299393347e-12),
        d=listOf(3.865634437153818e-7,1.5600523691049206e-7,3.055772727116593e-7,3.259443985376642e-7),
        isoStep=3200.0)
    private val teleBase = PersistedParametricNoiseModel(
        a=listOf(2.2439454161025505e-6,2.1932277754430343e-6,2.1942981610058283e-6,2.2174992392469146e-6),
        b=listOf(5.840659707704573e-6,1.8895493529814975e-5,2.006156735407995e-5,1.0101353512946843e-5),
        c=listOf(1.5905819830898838e-11,1.3419809758070684e-11,1.340131877811037e-11,1.5729558889055503e-11),
        d=listOf(4.224168359477573e-7,1.4814631104464933e-8,-2.331983540368266e-9,2.641752003039813e-7),
        isoStep=1118.0)
    private val frontBase = PersistedParametricNoiseModel(
        a=listOf(8.581585317602946e-7,8.456955201497131e-7,8.491433577720162e-7,8.525433961250139e-7),
        b=listOf(6.698401632196151e-6,1.2932924430757916e-5,1.159459581567086e-5,6.737335494321403e-6),
        c=listOf(2.278775855714765e-12,2.407700239539807e-12,2.528083390246599e-12,2.388436867599067e-12),
        d=listOf(3.084018589455176e-7,1.4690453404163644e-7,1.7458244241941856e-7,2.965434301862013e-7),
        isoStep=1600.0)

    private fun scaled(base: PersistedParametricNoiseModel, scale: Double) = PersistedParametricNoiseModel(
        a=base.a.map { it*scale }, b=base.b.map { it*scale },
        c=base.c.map { it*scale }, d=base.d.map { it*scale }, isoStep=base.isoStep)

    private fun group(prefix: String, group: NoiseModelPresetGroup, base: PersistedParametricNoiseModel, tiers: List<Tier>) =
        tiers.mapIndexed { index, tier -> NoiseModelPreset(
            id="bncam:${prefix}:${index+1}", displayName="${group.sectionTitle} · ${tier.suffix}",
            origin=NoiseModelPresetOrigin.BNCAM, model=scaled(base,tier.scale), group=group,
            description=tier.description)
        }

    val all: List<NoiseModelPreset> =
        group("main", NoiseModelPresetGroup.MAIN, mainBase, tiersMain) +
        group("ultra", NoiseModelPresetGroup.ULTRA_WIDE, ultraBase, tiersUltra) +
        group("tele", NoiseModelPresetGroup.TELE, teleBase, tiersTele) +
        group("front", NoiseModelPresetGroup.FRONT, frontBase, tiersFront)

    fun find(id: String?): NoiseModelPreset? = id?.let { key -> all.firstOrNull { it.id == key } }
    fun inGroup(group: NoiseModelPresetGroup): List<NoiseModelPreset> = all.filter { it.group == group }
}
