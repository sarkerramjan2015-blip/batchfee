// ===== BatchFee Auth Check & Session Management =====

const SESSION_KEY = 'batchfee_admin_session';

function getSession() {
    try {
        const data = sessionStorage.getItem(SESSION_KEY);
        return data ? JSON.parse(data) : null;
    } catch {
        return null;
    }
}

function setSession(userData) {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(userData));
}

function clearSession() {
    sessionStorage.removeItem(SESSION_KEY);
}

// Allowed roles that can access the admin panel
const ALLOWED_ROLES = ['SuperAdmin', 'InstituteOwner', 'InstituteAdmin'];

async function requireAuth() {
    return new Promise((resolve, reject) => {
        const unsubscribe = auth.onAuthStateChanged(async (user) => {
            unsubscribe();

            if (!user) {
                redirectToLogin();
                reject(new Error('Not authenticated'));
                return;
            }

            try {
                // Fetch user profile from app_users collection
                const userDoc = await db.collection('app_users').doc(user.uid).get();

                if (!userDoc.exists) {
                    await auth.signOut();
                    clearSession();
                    showError('Account not found. Please contact your administrator.');
                    reject(new Error('User not found in app_users'));
                    return;
                }

                const userData = userDoc.data();

                if (userData.status !== 'active') {
                    await auth.signOut();
                    clearSession();
                    showError('Your account has been deactivated. Please contact your administrator.');
                    reject(new Error('Account inactive'));
                    return;
                }

                if (!ALLOWED_ROLES.includes(userData.role)) {
                    await auth.signOut();
                    clearSession();
                    showError('You do not have permission to access the admin panel.');
                    reject(new Error('Unauthorized role'));
                    return;
                }

                // Also fetch institute data
                let instituteData = null;
                if (userData.instituteId) {
                    try {
                        const instDoc = await db.collection('institutes').doc(userData.instituteId).get();
                        if (instDoc.exists) {
                            instituteData = { id: instDoc.id, ...instDoc.data() };
                        }
                    } catch (e) {
                        console.warn('Failed to load institute data:', e);
                    }
                }

                const session = {
                    uid: user.uid,
                    email: user.email,
                    name: userData.name || user.email?.split('@')[0] || 'Admin',
                    role: userData.role,
                    instituteId: userData.instituteId || null,
                    institute: instituteData
                };

                setSession(session);

                // Update sidebar if present
                updateSidebarUser(session);
                resolve(session);
            } catch (err) {
                console.error('Auth check error:', err);
                reject(err);
            }
        });
    });
}

function redirectToLogin() {
    const currentPath = window.location.pathname;
    // Only redirect if not already on login page
    if (!currentPath.endsWith('login.html')) {
        const loginUrl = currentPath.includes('/admin/')
            ? 'login.html'
            : 'admin/login.html';
        window.location.href = loginUrl;
    }
}

function redirectToDashboard() {
    window.location.href = 'dashboard.html';
}

function showError(message) {
    // Try to show in login page error div first
    const errorEl = document.getElementById('loginError');
    if (errorEl) {
        errorEl.textContent = message;
        errorEl.classList.add('show');
    } else {
        alert(message);
    }
}

function updateSidebarUser(session) {
    const avatarEl = document.getElementById('sidebarUserAvatar');
    const nameEl = document.getElementById('sidebarUserName');
    const roleEl = document.getElementById('sidebarUserRole');
    const instEl = document.getElementById('sidebarInstituteName');

    if (avatarEl) {
        avatarEl.textContent = (session.name || 'A').charAt(0).toUpperCase();
    }
    if (nameEl) {
        nameEl.textContent = session.name;
    }
    if (roleEl) {
        const roleLabels = {
            'SuperAdmin': 'Super Admin',
            'InstituteOwner': 'Owner',
            'InstituteAdmin': 'Admin'
        };
        roleEl.textContent = roleLabels[session.role] || session.role;
    }
    if (instEl && session.institute) {
        instEl.textContent = session.institute.name || session.institute.instituteName || '';
    }
}

async function logoutUser() {
    try {
        await auth.signOut();
        clearSession();
        window.location.href = 'login.html';
    } catch (err) {
        console.error('Logout error:', err);
    }
}

// Note: Each admin page calls requireAuth() explicitly in its own script.
// The auto-run is intentionally removed to avoid duplicate onAuthStateChanged listeners.
