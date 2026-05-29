package pl.put.observationcompanion.domain.model

// Built-in PUT observation stations.
object BuiltInObservatories {
    val all: List<Preset> = listOf(
        Preset(
            name = "PUT Kąkolewo SX",
            groundLat = 52.236972,
            groundLon = 16.245590,
            groundAlt = 80.0,
            antennaBands = setOf(AntennaBand.S_BAND, AntennaBand.X_BAND)
        ),
        Preset(
            name = "PUT Kąkolewo VHF/UHF/C",
            groundLat = 52.237047,
            groundLon = 16.245134,
            groundAlt = 80.0,
            antennaBands = setOf(AntennaBand.VHF, AntennaBand.UHF, AntennaBand.C_BAND)
        ),
        Preset(
            name = "PUT Poznań",
            groundLat = 52.40218020706405,
            groundLon = 16.951582240886484,
            groundAlt = 80.0,
            antennaBands = setOf(AntennaBand.VHF, AntennaBand.UHF)
        )
    )
}
