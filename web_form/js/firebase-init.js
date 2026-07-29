// ===== BatchFee Firebase Initialization =====

const firebaseConfig = {
    apiKey: "AIzaSyD5Ksi9vr0jJjD5cKZ4okpEKmBgK2OVzTI",
    authDomain: "batchfee-477b8.firebaseapp.com",
    projectId: "batchfee-477b8",
    storageBucket: "batchfee-477b8.firebasestorage.app",
    messagingSenderId: "632446580863",
    appId: "1:632446580863:web:61dc55d96ede0dca3c674a"
};

firebase.initializeApp(firebaseConfig);
const auth = firebase.auth();
const db = firebase.firestore();

// Enable offline persistence (useful for slow connections)
db.enablePersistence().catch(function(err) {
    if (err.code === 'failed-precondition') {
        console.warn('Firestore persistence: Multiple tabs open, persistence enabled in one tab only.');
    } else if (err.code === 'unimplemented') {
        console.warn('Firestore persistence: Browser does not support it.');
    }
});
