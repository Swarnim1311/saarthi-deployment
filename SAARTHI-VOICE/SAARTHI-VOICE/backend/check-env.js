require("dotenv").config();

console.log(
    process.env.BHASHINI_API_KEY
        ? "API KEY FOUND"
        : "API KEY NOT FOUND"
);