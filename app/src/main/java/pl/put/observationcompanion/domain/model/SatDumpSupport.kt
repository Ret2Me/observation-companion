package pl.put.observationcompanion.domain.model

// Satellites that ship with a SatDump pipeline. SatDump's pipeline JSON files
// don't carry NORAD IDs - they index by satellite *name* - so we match the
// SatNOGS satellite name against a list of tokens derived from the 88
// pipeline filenames in
// https://github.com/SatDump/SatDump/tree/master/resources/pipelines
//
// Generic mission "labels" (Test, Others, Analog, DVB_Test, Work-In-Progress)
// are excluded - matching against those would flag almost everything.
//
// Some tokens also list common SatNOGS-side spellings (e.g. "FY-3" alongside
// "FengYun-3") so the substring check catches the satellite however SatNOGS
// chose to name it.
object SatDumpSupport {

    // Each entry is one acceptable substring (lowercased) - if the satellite
    // name contains it, SatDump has a pipeline that should be able to demod
    // the downlink.
    private val SUPPORTED_NAME_TOKENS: List<String> = listOf(
        // Pipeline filenames mapped 1:1
        "ace", "aditya", "aim", "aws", "bluewalker3", "blue walker",
        "chandrayaan", "cloudsat", "cluster", "coriolis", "cosmos",
        "cryosat", "dmsp", "dscovr", "earthcare", "edrs",
        "elektro", "arktika", "eos", "erminaz", "escapade",
        "fengyun", "fy-2", "fy-3", "fy-4",
        "formosat", "gcom", "geonetcast", "geoscan", "gk-2a", "gk2a",
        "goes", "gpm", "hera", "himawari", "hinode",
        "im-1", "inmarsat", "insat", "integral", "iris",
        "jason", "jpss", "noaa-20", "noaa-21", "suomi npp", "npp",
        "juice", "kanopus", "kplo", "landsat", "lucky7", "lucky-7",
        "mats", "meteor", "metop",
        "msg", "mtg", "meteosat",
        "noaa", "oceansat", "odin", "ominous", "orbcomm", "orion", "orx",
        "peregrine", "prefire", "proba", "psyche", "queqiao",
        "saocom", "saral", "seawifs", "sentinel-6", "slim",
        "sonate-2", "spaceteamsat", "spacex starlink", "stereo",
        "syracuse",
        "tgo", "tianwen", "tropics", "tubsat",
        "umka", "uvsq", "uvsqsat-ng",
        "veronika", "viasat", "wsf-m", "xmm-newton",
        // Common LEO weather siblings SatDump can demod that aren't in the
        // pipeline filename list verbatim
        "aqua", "terra",
    )

    fun isSupported(satelliteName: String?): Boolean {
        if (satelliteName.isNullOrBlank()) return false
        val lower = satelliteName.lowercase()
        return SUPPORTED_NAME_TOKENS.any { token -> lower.contains(token) }
    }

    // Convenience overload kept for callers that already have NORAD - we just
    // forward to the name match. Don't try to "smart-merge" NORAD with names:
    // SatDump's database is name-keyed, so NORAD is genuinely the wrong axis.
    fun isSupported(satelliteName: String?, noradId: String?): Boolean =
        isSupported(satelliteName)
}
