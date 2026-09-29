/**
 * ============================================================================
 * 🌾 SAARTHI - Farmer Database Module
 * ============================================================================
 * 
 * Simple, lightweight JSON-backed data layer for storing and retrieving farmer profiles.
 * Designed for easy standalone integration into the main SAARTHI project.
 * 
 * Data Schema:
 *  - farmerId: string (e.g. "F001")
 *  - farmerName: string (e.g. "Harpreet Singh")
 *  - crop: string (e.g. "Rice")
 *  - cropLocation: string (e.g. "Amritsar")
 *  - district: string (e.g. "Amritsar")
 *  - state: string (e.g. "Punjab")
 *  - landSize: string (optional, e.g. "5 acres")
 *  - sowingDate: string (optional, e.g. "2024-06-15")
 * ============================================================================
 */

const fs = require("fs");
const path = require("path");

const DATA_FILE_PATH = path.join(__dirname, "data", "farmers.json");

/**
 * Internal helper to read all farmer records from the JSON store.
 * @returns {Array<object>} List of farmer objects
 */
function readFarmersData() {
    try {
        if (!fs.existsSync(DATA_FILE_PATH)) {
            // If data directory or file doesn't exist, create it with empty array
            const dataDir = path.dirname(DATA_FILE_PATH);
            if (!fs.existsSync(dataDir)) {
                fs.mkdirSync(dataDir, { recursive: true });
            }
            fs.writeFileSync(DATA_FILE_PATH, JSON.stringify([], null, 2), "utf8");
            return [];
        }
        const fileContent = fs.readFileSync(DATA_FILE_PATH, "utf8");
        return JSON.parse(fileContent || "[]");
    } catch (error) {
        console.error("❌ Error reading farmers.json:", error);
        return [];
    }
}

/**
 * Internal helper to persist farmer records to the JSON store.
 * @param {Array<object>} farmers
 */
function writeFarmersData(farmers) {
    try {
        const dataDir = path.dirname(DATA_FILE_PATH);
        if (!fs.existsSync(dataDir)) {
            fs.mkdirSync(dataDir, { recursive: true });
        }
        fs.writeFileSync(DATA_FILE_PATH, JSON.stringify(farmers, null, 2), "utf8");
    } catch (error) {
        console.error("❌ Error writing farmers.json:", error);
        throw new Error("Failed to write farmer data to local database.");
    }
}

/**
 * Retrieves a single farmer profile by farmerId.
 * PRIVACY: Only returns the specific requested farmer record. Never exposes the full list.
 * 
 * @param {string} farmerId - Case-insensitive ID (e.g. "F001" or "f001")
 * @returns {object|null} Farmer record or null if not found
 */
function getFarmerById(farmerId) {
    if (!farmerId || typeof farmerId !== "string") {
        return null;
    }
    const cleanId = farmerId.trim().toLowerCase();
    const farmers = readFarmersData();
    const found = farmers.find(f => f.farmerId && f.farmerId.trim().toLowerCase() === cleanId);
    return found ? { ...found } : null;
}

/**
 * Validates and saves a new or existing farmer profile.
 * 
 * @param {object} farmerData - Farmer details
 * @returns {{success: boolean, farmer?: object, error?: string}}
 */
function saveFarmer(farmerData) {
    if (!farmerData || typeof farmerData !== "object") {
        return { success: false, error: "Invalid farmer payload." };
    }

    const {
        farmerId,
        farmerName,
        crop,
        cropLocation,
        district,
        state,
        landSize,
        sowingDate
    } = farmerData;

    // Validate required fields
    if (!farmerId || !farmerId.trim()) {
        return { success: false, error: "Farmer ID is required." };
    }
    if (!farmerName || !farmerName.trim()) {
        return { success: false, error: "Farmer Name is required." };
    }
    if (!crop || !crop.trim()) {
        return { success: false, error: "Crop is required." };
    }
    if (!cropLocation || !cropLocation.trim()) {
        return { success: false, error: "Crop Location is required." };
    }
    if (!district || !district.trim()) {
        return { success: false, error: "District is required." };
    }
    if (!state || !state.trim()) {
        return { success: false, error: "State is required." };
    }

    const cleanRecord = {
        farmerId: farmerId.trim(),
        farmerName: farmerName.trim(),
        crop: crop.trim(),
        cropLocation: cropLocation.trim(),
        district: district.trim(),
        state: state.trim(),
        landSize: (landSize && landSize.trim()) ? landSize.trim() : null,
        sowingDate: (sowingDate && sowingDate.trim()) ? sowingDate.trim() : null,
        updatedAt: new Date().toISOString()
    };

    const farmers = readFarmersData();
    const existingIndex = farmers.findIndex(
        f => f.farmerId && f.farmerId.trim().toLowerCase() === cleanRecord.farmerId.toLowerCase()
    );

    if (existingIndex >= 0) {
        // Update existing record
        farmers[existingIndex] = { ...farmers[existingIndex], ...cleanRecord };
    } else {
        // Append new record
        farmers.push(cleanRecord);
    }

    writeFarmersData(farmers);
    console.log(`✅ Farmer profile saved successfully: [${cleanRecord.farmerId}] ${cleanRecord.farmerName}`);
    return { success: true, farmer: cleanRecord };
}

module.exports = {
    getFarmerById,
    saveFarmer
};
