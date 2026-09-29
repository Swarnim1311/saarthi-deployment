/**
 * ============================================================================
 * 🌾 SAARTHI - Weather Context Hook (Modular Service)
 * ============================================================================
 * 
 * TASK 8: Weather Context Hook.
 * Note for Teammates integrating with main SAARTHI project:
 * 
 * Do NOT use an external live weather key right now.
 * This service takes `farmer.cropLocation` (and district/state) and returns structured
 * weather metadata that the agronomic advice engine uses.
 * 
 * When ready to connect a real weather provider (e.g. OpenWeatherMap, IMD, Tomorrow.io):
 * 1. Add your API credentials to `.env` (e.g. `WEATHER_API_KEY=...`)
 * 2. Replace the simulated weather payload in `getWeatherForLocation()` with your HTTP call.
 * ============================================================================
 */

/**
 * Retrieves weather forecast context for a farmer's crop location.
 * Currently provides simulated agro-weather context structured for seamless production swap.
 * 
 * @param {string} cropLocation - E.g. "Amritsar", "Vijayawada"
 * @param {string} [district] - E.g. "Amritsar", "NTR"
 * @param {string} [state] - E.g. "Punjab", "Andhra Pradesh"
 * @returns {Promise<object>} Structured weather context
 */
async function getWeatherForLocation(cropLocation, district = "", state = "") {
    const location = cropLocation || district || state || "Default Region";

    // Teammates: Integrate your real weather API call here:
    // const apiKey = process.env.WEATHER_API_KEY;
    // const response = await fetch(`https://api.weatherapi.com/v1/forecast.json?key=${apiKey}&q=${encodeURIComponent(location)}&days=3`);
    // const data = await response.json();

    return {
        location: location,
        district: district,
        state: state,
        // Simulated weather state for agronomic reasoning:
        forecast: "Scattered rainfall expected within the next 24 to 48 hours",
        rainLikely: true,
        expectedRainMm: 12,
        temperature: "31°C",
        humidity: "78%",
        isMock: true,
        noteForTeammates: "Ready for OpenWeatherMap / IMD API integration"
    };
}

module.exports = {
    getWeatherForLocation
};
