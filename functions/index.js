const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

// Helper: Haversine distance in km
function distanceInKm(lat1, lon1, lat2, lon2) {
  function toRad(x) {
    return x * Math.PI / 180;
  }

  const R = 6371; // Earth radius in km
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

// Trigger when a new hazard is created
exports.notifyNearbyUsers = functions.firestore
  .document("hazards/{hazardId}")
  .onCreate(async (snap, context) => {
    const data = snap.data();
    const hazardLat = data.lat;
    const hazardLon = data.lon;

    if (hazardLat == null || hazardLon == null) {
      console.log("Hazard has no location, skipping notification");
      return null;
    }

    const radiusKm = 5; // radius within which users should be notified

    const usersSnap = await admin.firestore().collection("users").get();

    const tokens = [];

    usersSnap.forEach(doc => {
      const u = doc.data();
      if (!u.lat || !u.lon || !u.fcmToken) return;

      const d = distanceInKm(hazardLat, hazardLon, u.lat, u.lon);
      if (d <= radiusKm) {
        tokens.push(u.fcmToken);
      }
    });

    console.log(`Found ${tokens.length} users within ${radiusKm} km`);

    if (tokens.length === 0) {
      return null;
    }

    const message = {
      notification: {
        title: "Hazard detected in your area",
        body: "A road hazard was reported near your location. Drive safe!",
      },
      tokens: tokens,
    };

    try {
      const resp = await admin.messaging().sendMulticast(message);
      console.log("Notifications sent:", resp.successCount);
    } catch (e) {
      console.error("Error sending notifications", e);
    }

    return null;
  });
