package com.bncam.data.settings

/**
 * AGC 8.8.224 V12 built-in AWB preset catalog.
 *
 * Values are the RG/BG calibration vectors reconstructed from libagc.so PreComputedAWB.
 * Preset ids and display names follow the APK awb_entries/awb_entryvalues resources.
 */
data class AgcAwbPreset(
    val id: Int,
    val name: String,
    val points: List<GcamAwbCalibrationPoint>,
    val grGbRatio: Float? = null
) {
    val selectionLabel: String get() = id.toString().padStart(2, '0') + " · " + name
}

object AgcAwbPresetCatalog {
    private fun preset(
        id: Int,
        name: String,
        rg: FloatArray,
        bg: FloatArray,
        grGbRatio: Float? = null
    ): AgcAwbPreset {
        require(rg.size == bg.size && rg.size >= 2)
        return AgcAwbPreset(
            id = id,
            name = name,
            points = rg.indices.map { index -> GcamAwbCalibrationPoint(rg[index], bg[index]) },
            grGbRatio = grGbRatio
        )
    }

    val all: List<AgcAwbPreset> = listOf(
        preset(
            id = 0,
            name = "Google Pixel 2",
            rg = floatArrayOf(0.478984892f, 0.442842007f, 0.887744069f, 0.645941138f, 0.58401686f, 1.11317551f, 0.558293045f, 0.645941138f, 0.538162529f, 0.478984892f, 0.967126369f, 0.867480516f, 0.478984892f, 0.478984892f, 0.645941138f, 0.645941138f),
            bg = floatArrayOf(0.68641609f, 0.769319236f, 0.340845466f, 0.446765453f, 0.397625208f, 0.269750863f, 0.556984365f, 0.446765453f, 0.592109501f, 0.68641609f, 0.250332326f, 0.340845466f, 0.68641609f, 0.68641609f, 0.446765453f, 0.446765453f),
            grGbRatio = 1.00292969f
        ),
        preset(
            id = 1,
            name = "Google Pixel 2 Front",
            rg = floatArrayOf(0.454195052f, 0.442378104f, 0.800871074f, 0.595232248f, 0.55316937f, 1.02651429f, 0.523148477f, 0.595232248f, 0.523148477f, 0.454195052f, 0.800871074f, 0.782337606f, 0.454195052f, 0.454195052f, 0.595232248f, 0.595232248f),
            bg = floatArrayOf(0.721461236f, 0.759226203f, 0.375229508f, 0.458977729f, 0.444653243f, 0.284124434f, 0.594066083f, 0.458977729f, 0.594066083f, 0.721461236f, 0.375229508f, 0.374719501f, 0.721461236f, 0.721461236f, 0.458977729f, 0.458977729f),
            grGbRatio = 1.00097656f
        ),
        preset(
            id = 2,
            name = "Google Pixel 3",
            rg = floatArrayOf(0.505859375f, 0.627929688f, 0.731445312f),
            bg = floatArrayOf(0.655273438f, 0.526367188f, 0.348632812f),
            grGbRatio = null
        ),
        preset(
            id = 3,
            name = "Google Pixel 3a",
            rg = floatArrayOf(0.513671875f, 0.630859375f, 0.73828125f),
            bg = floatArrayOf(0.666015625f, 0.525390625f, 0.357421875f),
            grGbRatio = null
        ),
        preset(
            id = 4,
            name = "Google Pixel 4",
            rg = floatArrayOf(0.45703125f, 0.5703125f, 0.666992188f),
            bg = floatArrayOf(0.654296875f, 0.524414062f, 0.350585938f),
            grGbRatio = null
        ),
        preset(
            id = 5,
            name = "Google Pixel 4 Front",
            rg = floatArrayOf(0.486328125f, 0.61328125f, 0.71875f),
            bg = floatArrayOf(0.671875f, 0.525390625f, 0.350585938f),
            grGbRatio = null
        ),
        preset(
            id = 6,
            name = "Google Pixel 4 Tele",
            rg = floatArrayOf(0.532226562f, 0.661132812f, 0.76171875f),
            bg = floatArrayOf(0.607421875f, 0.4765625f, 0.31640625f),
            grGbRatio = null
        ),
        preset(
            id = 7,
            name = "Google Pixel 5 Main",
            rg = floatArrayOf(0.466796875f, 0.580078125f, 0.685546875f),
            bg = floatArrayOf(0.662109375f, 0.528320312f, 0.358398438f),
            grGbRatio = 1f
        ),
        preset(
            id = 8,
            name = "Google Pixel 5 Wide",
            rg = floatArrayOf(0.500976562f, 0.616210938f, 0.7265625f),
            bg = floatArrayOf(0.6796875f, 0.53515625f, 0.358398438f),
            grGbRatio = 1.00490677f
        ),
        preset(
            id = 9,
            name = "Google Pixel 5 Tele",
            rg = floatArrayOf(0.51953125f, 0.64453125f, 0.754882812f),
            bg = floatArrayOf(0.609375f, 0.484375f, 0.331054688f),
            grGbRatio = 0.990234375f
        ),
        preset(
            id = 10,
            name = "Google Pixel 5 Front",
            rg = floatArrayOf(0.500976562f, 0.615234375f, 0.736328125f),
            bg = floatArrayOf(0.706054688f, 0.553710938f, 0.374023438f),
            grGbRatio = null
        ),
        preset(
            id = 11,
            name = "Google Pixel 6 Main",
            rg = floatArrayOf(0.461914062f, 0.580078125f, 0.670898438f),
            bg = floatArrayOf(0.739257812f, 0.583984375f, 0.407226562f),
            grGbRatio = 1f
        ),
        preset(
            id = 12,
            name = "Google Pixel 6 Wide",
            rg = floatArrayOf(0.461914062f, 0.583984375f, 0.688476562f),
            bg = floatArrayOf(0.608398438f, 0.4609375f, 0.295898438f),
            grGbRatio = 1.00490677f
        ),
        preset(
            id = 13,
            name = "Google Pixel 6 Tele",
            rg = floatArrayOf(0.49609375f, 0.622070312f, 0.709960938f),
            bg = floatArrayOf(0.661132812f, 0.538085938f, 0.352539062f),
            grGbRatio = 0.990234375f
        ),
        preset(
            id = 14,
            name = "Google Pixel 6 Front",
            rg = floatArrayOf(0.563476562f, 0.701171875f, 0.814453125f),
            bg = floatArrayOf(0.5625f, 0.44140625f, 0.299804688f),
            grGbRatio = 1f
        ),
        preset(
            id = 15,
            name = "Sony IMX350",
            rg = floatArrayOf(0.514226019f, 0.478426993f, 0.886367023f, 0.654551029f, 0.562002003f, 1.09939003f, 0.586991012f, 0.614175975f, 1.33868003f),
            bg = floatArrayOf(0.701416016f, 0.742478013f, 0.366216004f, 0.420911998f, 0.415197998f, 0.298646003f, 0.585614026f, 0.418379009f, 0.214448005f),
            grGbRatio = null
        ),
        preset(
            id = 16,
            name = "Sony IMX361",
            rg = floatArrayOf(0.565999985f, 0.49000001f, 0.901087999f, 0.767543018f, 0.651921988f, 1.27515996f, 0.617227018f, 0.709731996f),
            bg = floatArrayOf(0.680000007f, 0.810000002f, 0.381401002f, 0.432482004f, 0.438026011f, 0.309179008f, 0.582874f, 0.435254008f),
            grGbRatio = null
        ),
        preset(
            id = 17,
            name = "Sony IMX363",
            rg = floatArrayOf(0.448004007f, 0.415865988f, 0.766839981f, 0.628225982f, 0.539857984f, 0.936038971f, 0.510996997f, 0.584042013f),
            bg = floatArrayOf(0.73397702f, 0.784987986f, 0.364499003f, 0.447109997f, 0.440643013f, 0.295309991f, 0.604970992f, 0.443877012f),
            grGbRatio = null
        ),
        preset(
            id = 18,
            name = "Sony IMX371",
            rg = floatArrayOf(0.419770986f, 0.402902007f, 0.770071983f, 0.596313f, 0.508363008f, 0.946595013f, 0.499439001f, 0.344865412f, 0.474400014f, 0.499439001f),
            bg = floatArrayOf(0.704199016f, 0.754097998f, 0.361164987f, 0.465793997f, 0.459120989f, 0.300112009f, 0.592091024f, 0.490652412f, 0.653199971f, 0.592091024f),
            grGbRatio = null
        ),
        preset(
            id = 19,
            name = "Sony IMX398",
            rg = floatArrayOf(0.461593986f, 0.442535013f, 0.824874997f, 0.640595019f, 0.557190001f, 1.02980006f, 0.540192008f, 0.640595019f, 0.521560013f, 0.48259899f),
            bg = floatArrayOf(0.622039974f, 0.665497005f, 0.341430992f, 0.413619995f, 0.385619998f, 0.278243989f, 0.525101006f, 0.413619995f, 0.580078006f, 0.448137999f),
            grGbRatio = null
        ),
        preset(
            id = 20,
            name = "Sony IMX471",
            rg = floatArrayOf(0.44382f, 0.404442996f, 0.851451993f, 0.598475993f, 0.519671023f, 1.06551003f, 0.50435102f, 0.559072971f),
            bg = floatArrayOf(0.727698028f, 0.782586992f, 0.371473014f, 0.461943001f, 0.437997997f, 0.29162699f, 0.610612988f, 0.449970007f),
            grGbRatio = null
        ),
        preset(
            id = 21,
            name = "Sony IMX586_OFILM",
            rg = floatArrayOf(0.483146012f, 0.448772013f, 0.876532972f, 0.669941008f, 0.592501998f, 1.07277f, 0.568499029f, 0.631220996f),
            bg = floatArrayOf(0.622057021f, 0.65260601f, 0.333155006f, 0.409754008f, 0.386875987f, 0.272592008f, 0.524326026f, 0.398315012f),
            grGbRatio = null
        ),
        preset(
            id = 22,
            name = "Sony IMX586_SEMCO",
            rg = floatArrayOf(0.479777992f, 0.442409992f, 0.856835008f, 0.654489994f, 0.576219976f, 1.05122995f, 0.560284972f, 0.615355015f),
            bg = floatArrayOf(0.603404999f, 0.632865012f, 0.33435899f, 0.392793f, 0.374112993f, 0.277815998f, 0.509319007f, 0.383453012f),
            grGbRatio = null
        ),
        preset(
            id = 23,
            name = "Sony IMX586_CEPHEUS",
            rg = floatArrayOf(0.493319005f, 0.454778999f, 0.901987016f, 0.623090982f, 0.557147026f, 1.10215998f, 0.565421999f, 0.590119004f),
            bg = floatArrayOf(0.642238021f, 0.683575988f, 0.347467989f, 0.412712991f, 0.417198002f, 0.287979007f, 0.535637975f, 0.414956003f),
            grGbRatio = null
        ),
        preset(
            id = 24,
            name = "Sony IMX586_VIOLET",
            rg = floatArrayOf(0.502900004f, 0.457500011f, 0.903439999f, 0.623409986f, 0.552900016f, 1.04999995f, 0.586399972f, 0.59762001f),
            bg = floatArrayOf(0.640717983f, 0.686269999f, 0.337900013f, 0.413500011f, 0.39410001f, 0.285699993f, 0.537029982f, 0.416251987f),
            grGbRatio = null
        ),
        preset(
            id = 25,
            name = "Sony IMX586_SUNNY",
            rg = floatArrayOf(0.550000012f, 0.51700002f, 0.859000027f, 0.672999978f, 0.596000016f, 1.03299999f, 0.619000018f, 0.634500027f),
            bg = floatArrayOf(0.637000024f, 0.671999991f, 0.356000006f, 0.456999987f, 0.421000004f, 0.300000012f, 0.521000028f, 0.43900001f),
            grGbRatio = null
        ),
        preset(
            id = 26,
            name = "Sony IMX586",
            rg = floatArrayOf(0.5f, 0.467000008f, 0.809000015f, 0.623000026f, 0.546000004f, 0.98299998f, 0.569000006f, 0.584500015f),
            bg = floatArrayOf(0.637000024f, 0.671999991f, 0.356000006f, 0.456999987f, 0.421000004f, 0.300000012f, 0.521000028f, 0.43900001f),
            grGbRatio = null
        ),
        preset(
            id = 27,
            name = "Sony IMX686",
            rg = floatArrayOf(0.466100991f, 0.435938001f, 0.754287004f, 0.584587991f, 0.525802016f, 0.980013013f, 0.525940001f, 0.555194974f),
            bg = floatArrayOf(0.709160984f, 0.751532018f, 0.378995001f, 0.434882998f, 0.443001986f, 0.311971992f, 0.581330001f, 0.438941985f),
            grGbRatio = null
        ),
        preset(
            id = 28,
            name = "Sony IMX689",
            rg = floatArrayOf(0.423171997f, 0.403503001f, 0.758925974f, 0.590789974f, 0.524093986f, 0.936233997f, 0.499709994f, 0.590789974f, 0.47706601f, 0.499709994f),
            bg = floatArrayOf(0.733475029f, 0.810410023f, 0.400070012f, 0.493256003f, 0.488068014f, 0.327591002f, 0.607528985f, 0.493256003f, 0.662872016f, 0.607528985f),
            grGbRatio = null
        ),
        preset(
            id = 29,
            name = "Sony IMX689_MOD",
            rg = floatArrayOf(0.423171997f, 0.403503001f, 0.590789974f, 0.524093986f, 0.936233997f, 0.499709994f, 0.590789974f, 0.47706601f, 0.499709994f, 0.758925974f),
            bg = floatArrayOf(0.733475029f, 0.810410023f, 0.493256003f, 0.488068014f, 0.327591002f, 0.607528985f, 0.493256003f, 0.662872016f, 0.607528985f, 0.400070012f),
            grGbRatio = null
        ),
        preset(
            id = 30,
            name = "Samsung S5K2L7",
            rg = floatArrayOf(0.389999986f, 0.358999997f, 0.667999983f, 0.521000028f, 0.462000012f, 0.802999973f, 0.442999989f, 0.492000014f, 0.950999975f),
            bg = floatArrayOf(0.783999979f, 0.828999996f, 0.439999998f, 0.504000008f, 0.500999987f, 0.382999986f, 0.666999996f, 0.501999974f, 0.308999985f),
            grGbRatio = null
        ),
        preset(
            id = 31,
            name = "Samsung S5K3L6",
            rg = floatArrayOf(0.417374998f, 0.377317011f, 0.731555998f, 0.567270994f, 0.479501992f, 0.887887001f, 0.477429003f, 0.523386002f),
            bg = floatArrayOf(0.706413984f, 0.746163011f, 0.396131992f, 0.463384002f, 0.448406011f, 0.340158999f, 0.604596019f, 0.455895007f),
            grGbRatio = null
        ),
        preset(
            id = 32,
            name = "Samsung S5K3T2",
            rg = floatArrayOf(0.447899997f, 0.447899997f, 0.801299989f, 0.655600011f, 0.576900005f, 0.985400021f, 0.520099998f, 0.611199975f),
            bg = floatArrayOf(0.720099986f, 0.76639998f, 0.374599993f, 0.476099998f, 0.449800014f, 0.312099993f, 0.604399979f, 0.462900013f),
            grGbRatio = null
        ),
        preset(
            id = 33,
            name = "Samsung S5KGD1",
            rg = floatArrayOf(0.507121027f, 0.459226012f, 0.921465993f, 0.74000001f, 0.613063991f, 1.13066006f, 0.58999902f, 0.676531971f, 1.47604001f),
            bg = floatArrayOf(0.705950022f, 0.775950015f, 0.40633899f, 0.458000004f, 0.443944991f, 0.342976004f, 0.584820986f, 0.450973004f, 0.268976003f),
            grGbRatio = null
        ),
        preset(
            id = 34,
            name = "Samsung S5KGM1",
            rg = floatArrayOf(0.388668001f, 0.333214015f, 0.707638025f, 0.538015008f, 0.481287003f, 0.910000026f, 0.461145014f, 0.538015008f, 0.450537354f, 0.388668001f, 0.839999974f, 0.705769002f, 0.391618013f, 0.388668001f, 0.538015008f, 0.538015008f),
            bg = floatArrayOf(0.658303022f, 0.705075026f, 0.32565999f, 0.406785011f, 0.389555007f, 0.230000004f, 0.530103981f, 0.406785011f, 0.562027156f, 0.658303022f, 0.25f, 0.30853501f, 0.654631019f, 0.658303022f, 0.406785011f, 0.406785011f),
            grGbRatio = null
        ),
        preset(
            id = 35,
            name = "Samsung S5KGM1 (rn7)",
            rg = floatArrayOf(0.49000001f, 0.414999992f, 0.869400024f, 0.720099986f, 0.595099986f, 1.06110001f, 0.570599973f, 0.720099986f, 0.550599992f, 0.570599973f, 0.869400024f, 0.869400024f, 0.442900002f, 0.442900002f, 0.593999982f, 0.593999982f),
            bg = floatArrayOf(0.668500006f, 0.735400021f, 0.375400007f, 0.449999988f, 0.441399992f, 0.296600014f, 0.565199971f, 0.449999988f, 0.615199983f, 0.565199971f, 0.375400007f, 0.375400007f, 0.701399982f, 0.701399982f, 0.435900003f, 0.435900003f),
            grGbRatio = null
        ),
        preset(
            id = 36,
            name = "Samsung S5KHMX",
            rg = floatArrayOf(0.482259989f, 0.446229994f, 0.847235978f, 0.635086f, 0.564103007f, 1.01823997f, 0.551392019f, 0.59959501f),
            bg = floatArrayOf(0.64665997f, 0.685239971f, 0.378233999f, 0.421162993f, 0.418487012f, 0.329795986f, 0.551195025f, 0.419824988f),
            grGbRatio = null
        ),
        preset(
            id = 37,
            name = "OmniVision OV02A10",
            rg = floatArrayOf(0.554363012f, 0.525561988f, 0.949847996f, 0.686363995f, 0.640348017f, 1.13935995f, 0.601375997f, 0.663356006f, 1.41499996f),
            bg = floatArrayOf(0.807603002f, 0.858994007f, 0.524492025f, 0.58859098f, 0.563856006f, 0.469361007f, 0.693358004f, 0.576224029f, 0.389171004f),
            grGbRatio = null
        ),
        preset(
            id = 38,
            name = "OmniVision OV08A10",
            rg = floatArrayOf(0.424300998f, 0.38776499f, 0.778232992f, 0.577003002f, 0.522306025f, 0.968169987f, 0.492163986f, 0.54965502f, 1.15804994f),
            bg = floatArrayOf(0.66227001f, 0.692893982f, 0.397064f, 0.446815014f, 0.440703988f, 0.345403999f, 0.573619008f, 0.443760008f, 0.289999992f),
            grGbRatio = null
        ),
        preset(
            id = 39,
            name = "OmniVision OV12A10",
            rg = floatArrayOf(0.437575996f, 0.397664011f, 0.825447023f, 0.618125975f, 0.561469018f, 1.30472231f, 0.517921984f, 0.618125975f, 0.480091006f, 0.517921984f, 1.03088903f, 0.825447023f, 0.517921984f, 0.517921984f, 0.618125975f, 0.618125975f),
            bg = floatArrayOf(0.740225017f, 0.772494972f, 0.440795988f, 0.495249987f, 0.509199023f, 0.325706005f, 0.618866026f, 0.495249987f, 0.699002981f, 0.618866026f, 0.370225012f, 0.440795988f, 0.618866026f, 0.618866026f, 0.495249987f, 0.495249987f),
            grGbRatio = null
        ),
        preset(
            id = 40,
            name = "OmniVision OV13855",
            rg = floatArrayOf(0.43900001f, 0.407000005f, 0.74000001f, 0.60799998f, 0.558000028f, 0.912999988f, 0.495000005f, 0.583000004f),
            bg = floatArrayOf(0.75f, 0.787f, 0.448000014f, 0.572000027f, 0.52700001f, 0.393000007f, 0.624000013f, 0.549499989f),
            grGbRatio = null
        ),
        preset(
            id = 41,
            name = "OmniVision OV13880",
            rg = floatArrayOf(0.475279987f, 0.430453002f, 0.900970995f, 0.642090023f, 0.528841972f, 1.30999994f, 0.555128992f, 0.642090023f, 0.521164f, 0.555128992f, 1.12469995f, 0.900970995f, 0.555128992f, 0.555128992f, 0.642090023f, 0.642090023f),
            bg = floatArrayOf(0.77629298f, 0.793518007f, 0.467357993f, 0.539452016f, 0.577755988f, 0.403338999f, 0.650488973f, 0.539452016f, 0.691968024f, 0.650488973f, 0.407377005f, 0.467357993f, 0.650488973f, 0.650488973f, 0.539452016f, 0.539452016f),
            grGbRatio = null
        ),
        preset(
            id = 42,
            name = "OmniVision OV13880 (rn7)",
            rg = floatArrayOf(0.404799998f, 0.381500006f, 0.776600003f, 0.592199981f, 0.555599988f, 0.977699995f, 0.4815f, 0.592199981f, 0.503754973f, 0.4815f, 0.776600003f, 0.776600003f, 0.4815f, 0.4815f, 0.592199981f, 0.592199981f),
            bg = floatArrayOf(0.72420001f, 0.744799972f, 0.442600012f, 0.503700018f, 0.509800017f, 0.340999991f, 0.614000022f, 0.503700018f, 0.626013994f, 0.614000022f, 0.442600012f, 0.170518756f, 0.614000022f, 0.614000022f, 0.503700018f, 0.503700018f),
            grGbRatio = null
        ),
        preset(
            id = 43,
            name = "OV48C - Mi10U MAIN",
            rg = floatArrayOf(0.67335099f, 0.711175025f, 0.380623013f, 0.422738999f, 0.426499009f, 0.315838993f, 0.57388097f, 0.424618989f),
            bg = floatArrayOf(0.419820994f, 0.384342998f, 0.787321985f, 0.561030984f, 0.479797006f, 0.973773003f, 0.481947005f, 0.520413995f),
            grGbRatio = null
        ),
        preset(
            id = 44,
            name = "IMX586 - Mi10U 5X",
            rg = floatArrayOf(0.484245986f, 0.442288011f, 0.870696008f, 0.621203005f, 0.555014014f, 1.069718f, 0.540000021f, 0.588109016f),
            bg = floatArrayOf(0.655807018f, 0.69984901f, 0.34207201f, 0.402563006f, 0.413221002f, 0.285479993f, 0.547878027f, 0.407891989f),
            grGbRatio = null
        ),
        preset(
            id = 45,
            name = "S5K3T2 - Mi10U FRONT",
            rg = floatArrayOf(0.482582986f, 0.444689006f, 0.871024013f, 0.699205995f, 0.609129012f, 1.06777203f, 0.564590991f, 0.65416801f),
            bg = floatArrayOf(0.747739017f, 0.81773901f, 0.391467005f, 0.456625015f, 0.451375991f, 0.326503009f, 0.596248984f, 0.454001009f),
            grGbRatio = null
        ),
        preset(
            id = 46,
            name = "S5K2L7 - Mi10U 2X",
            rg = floatArrayOf(0.392237991f, 0.360502988f, 0.696034014f, 0.523379028f, 0.452645987f, 0.854574978f, 0.450369f, 0.488012999f),
            bg = floatArrayOf(0.785851002f, 0.828869998f, 0.443957001f, 0.509968996f, 0.505075991f, 0.378329009f, 0.666691005f, 0.505536973f),
            grGbRatio = null
        ),
        preset(
            id = 47,
            name = "IMX350 - Mi10U WIDE",
            rg = floatArrayOf(0.468549013f, 0.433434993f, 0.828692019f, 0.623995006f, 0.530180991f, 1.01431501f, 0.534816027f, 0.577087998f),
            bg = floatArrayOf(0.642907023f, 0.683413982f, 0.351556003f, 0.398209989f, 0.388520002f, 0.293114007f, 0.542488992f, 0.393364996f),
            grGbRatio = null
        ),
        preset(
            id = 48,
            name = "Sony IMX355_OFILM",
            rg = floatArrayOf(0.423478991f, 0.398656994f, 0.750045002f, 0.548327982f, 0.491091013f, 0.922937989f, 0.481406003f, 0.519708991f),
            bg = floatArrayOf(0.741414011f, 0.802601993f, 0.406455994f, 0.48546499f, 0.482484996f, 0.333375007f, 0.653059006f, 0.483974993f),
            grGbRatio = null
        ),
        preset(
            id = 49,
            name = "Sony IMX355_SUNNY",
            rg = floatArrayOf(0.423478991f, 0.398656994f, 0.765044987f, 0.548327982f, 0.491091013f, 0.94293803f, 0.476406008f, 0.519708991f),
            bg = floatArrayOf(0.701413989f, 0.752601981f, 0.386456013f, 0.465465009f, 0.452484995f, 0.313374996f, 0.623058975f, 0.458974987f),
            grGbRatio = null
        ),
        preset(
            id = 50,
            name = "Sony IMX471",
            rg = floatArrayOf(0.425309986f, 0.401749998f, 0.752857983f, 0.584670007f, 0.522773981f, 0.933812022f, 0.49338001f, 0.555372f),
            bg = floatArrayOf(0.685239971f, 0.745039999f, 0.349234015f, 0.40625f, 0.402170002f, 0.276212007f, 0.576719999f, 0.404210001f),
            grGbRatio = null
        ),
        preset(
            id = 51,
            name = "OmniVision OV5675",
            rg = floatArrayOf(0.545273006f, 0.509414017f, 0.869837999f, 0.680109978f, 0.656608999f, 1.03577602f, 0.596898019f, 0.668359995f),
            bg = floatArrayOf(0.759199023f, 0.812093973f, 0.499933988f, 0.571263015f, 0.562267005f, 0.445964009f, 0.684800029f, 0.56676501f),
            grGbRatio = null
        ),
        preset(
            id = 52,
            name = "Samsung S5KHM2_OFILM",
            rg = floatArrayOf(0.561235011f, 0.520519018f, 0.945478022f, 0.708371997f, 0.651625991f, 1.12295604f, 0.634672999f, 0.679998994f),
            bg = floatArrayOf(0.70560497f, 0.777531981f, 0.423932999f, 0.458635986f, 0.472972006f, 0.357962012f, 0.619683981f, 0.465804011f),
            grGbRatio = null
        ),
        preset(
            id = 53,
            name = "Samsung S5KHM2_SUNNY",
            rg = floatArrayOf(0.476062f, 0.440108001f, 0.807025015f, 0.630954027f, 0.570087016f, 0.975760996f, 0.542666018f, 0.600521028f),
            bg = floatArrayOf(0.621949017f, 0.671702981f, 0.382550001f, 0.427585989f, 0.421705008f, 0.327107012f, 0.553637028f, 0.42464599f),
            grGbRatio = null
        ),
        preset(
            id = 54,
            name = "Samsung S5KGW3",
            rg = floatArrayOf(0.585399985f, 0.533340991f, 0.980300009f, 0.781199992f, 0.712000012f, 1.18299997f, 0.66900003f, 0.746599972f),
            bg = floatArrayOf(0.642329991f, 0.699147999f, 0.394499987f, 0.45570001f, 0.43215999f, 0.347000003f, 0.560000002f, 0.443980008f),
            grGbRatio = null
        ),
        preset(
            id = 55,
            name = "Samsung S21+Snap",
            rg = floatArrayOf(0.502251983f, 0.458903998f, 0.885389984f, 0.677046001f, 0.592375994f, 1.08282602f, 0.567694008f, 0.622789979f),
            bg = floatArrayOf(0.648442984f, 0.692210972f, 0.34830001f, 0.428799003f, 0.415407002f, 0.292153001f, 0.548855007f, 0.415787995f),
            grGbRatio = null
        )
    )

    init {
        require(all.size == 56) { "AGC V12 AWB preset catalog must contain exactly 56 entries" }
        require(all.map { it.id } == (0..55).toList()) { "AGC V12 AWB preset ids must be contiguous 0..55" }
    }

    fun byId(id: Int): AgcAwbPreset? = all.getOrNull(id)?.takeIf { it.id == id }
    fun byName(name: String): AgcAwbPreset? = all.firstOrNull { it.name == name }
    fun bySelectionLabel(label: String): AgcAwbPreset? = all.firstOrNull { it.selectionLabel == label }
}
