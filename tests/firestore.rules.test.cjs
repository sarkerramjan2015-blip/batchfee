const { readFileSync } = require("node:fs");
const assert = require("node:assert/strict");
const { after, before, beforeEach, describe, test } = require("node:test");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {
  collection,
  collectionGroup,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
} = require("firebase/firestore");

const PROJECT_ID = "demo-batchfee-rules";
const OWNER_A = "owner-a";
const OWNER_B = "owner-b";
const ADMIN = "super-admin";

let testEnv;

function authDb(uid, email = `${uid}@example.test`, claims = {}) {
  return testEnv.authenticatedContext(uid, { email, ...claims }).firestore();
}

function instituteRef(db, instituteId) {
  return doc(db, "institutes", instituteId);
}

function tenantDoc(db, instituteId, collectionName, documentId) {
  return doc(db, "institutes", instituteId, collectionName, documentId);
}

function validNewInstitute(email, createdAt = Date.now()) {
  return {
    instituteName: "New Institute",
    ownerName: "New Owner",
    email,
    whatsappNumber: "+8801700000000",
    role: "owner",
    createdAt,
    isActive: true,
    trialEndDate: createdAt + 2_592_000_000,
    currentPeriodEndMs: createdAt + 2_592_000_000,
    currentPlanId: "plan_free_trial",
    subscriptionStatus: "trial",
    studentLimit: 0,
    staffLimit: 1,
    studentCount: 0,
    staffCount: 0,
    batchCount: 0,
  };
}

async function seedBaseData() {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();

    await setDoc(instituteRef(db, OWNER_A), {
      instituteName: "Institute A",
      ownerName: "Owner A",
      email: "owner-a@example.test",
      role: "owner",
      createdAt: Date.now(),
      isActive: true,
      trialEndDate: Date.now() + 2_592_000_000,
      currentPeriodEndMs: Date.now() + 2_592_000_000,
      currentPlanId: "plan_growth",
      subscriptionStatus: "active",
      studentLimit: 500,
      staffLimit: 20,
      securityPin: "1234",
      studentCount: 1,
      staffCount: 6,
      batchCount: 1,
    });
    await setDoc(instituteRef(db, OWNER_B), {
      instituteName: "Institute B",
      ownerName: "Owner B",
      email: "owner-b@example.test",
      role: "owner",
      createdAt: Date.now(),
      isActive: true,
      trialEndDate: Date.now() + 2_592_000_000,
      currentPeriodEndMs: Date.now() + 2_592_000_000,
      currentPlanId: "plan_pro",
      subscriptionStatus: "active",
      studentLimit: 1000,
      staffLimit: 50,
      securityPin: "9876",
      studentCount: 1,
      staffCount: 0,
      batchCount: 1,
    });
    await setDoc(instituteRef(db, "blocked-institute"), {
      instituteName: "Blocked Institute",
      isActive: false,
    });
    await setDoc(instituteRef(db, "expired-institute"), {
      instituteName: "Expired Institute",
      ownerName: "Expired Owner",
      email: "expired-owner@example.test",
      role: "owner",
      isActive: true,
      currentPlanId: "plan_free_trial",
      subscriptionStatus: "expired",
      currentPeriodEndMs: Date.now() - 60_000,
      studentLimit: 50,
      staffLimit: 1,
    });

    await setDoc(doc(db, "app_users", ADMIN), {
      role: "SuperAdmin",
      status: "active",
      instituteId: ADMIN,
    });
    await setDoc(doc(db, "app_users", "institute-admin-a"), {
      role: "InstituteAdmin",
      status: "active",
      instituteId: OWNER_A,
    });
    await setDoc(doc(db, "app_users", "inactive-institute-admin-a"), {
      role: "InstituteAdmin",
      status: "inactive",
      instituteId: OWNER_A,
    });

    const staffRecords = {
      "staff-view-a": { status: "active", archivedAtMs: null, permissions: "view_student" },
      "staff-manage-a": { status: "active", archivedAtMs: null, permissions: "manage_student" },
      "staff-collect-a": { status: "active", archivedAtMs: null, permissions: "collect_fee,view_fee_summary" },
      "staff-none-a": { status: "active", archivedAtMs: null, permissions: "" },
      "staff-inactive-a": { status: "inactive", archivedAtMs: null, permissions: "view_student" },
      "staff-blocked-a": { status: "blocked", archivedAtMs: null, permissions: "view_student" },
      "staff-archived-a": { status: "active", archivedAtMs: Date.now(), permissions: "view_student" },
      "staff-fake-token-a": { status: "active", archivedAtMs: null, permissions: "view_student_extra" },
    };
    for (const [staffId, data] of Object.entries(staffRecords)) {
      await setDoc(tenantDoc(db, OWNER_A, "staffs", staffId), data);
    }
    await setDoc(tenantDoc(db, "blocked-institute", "staffs", "staff-active-blocked-institute"), {
      status: "active",
      archivedAtMs: null,
      permissions: "view_student",
    });

    await setDoc(tenantDoc(db, OWNER_A, "students", "student-a"), {
      instituteId: OWNER_A,
      fullName: "Student A",
    });
    await setDoc(tenantDoc(db, OWNER_B, "students", "student-b"), {
      instituteId: OWNER_B,
      fullName: "Student B",
    });
    await setDoc(tenantDoc(db, OWNER_A, "students", "student-doc-a1"), {
      instituteId: OWNER_A,
      studentCode: "10001",
      fullName: "Linked Student A1",
      status: "active",
      archivedAtMs: null,
      isAppAccessEnabled: true,
      firebaseUid: "student-uid-a1",
    });
    await setDoc(tenantDoc(db, OWNER_A, "students", "student-doc-a2"), {
      instituteId: OWNER_A,
      studentCode: "10002",
      fullName: "Linked Student A2",
      status: "active",
      archivedAtMs: null,
      isAppAccessEnabled: true,
      firebaseUid: "student-uid-a2",
    });
    await setDoc(tenantDoc(db, OWNER_B, "students", "student-doc-b1"), {
      instituteId: OWNER_B,
      studentCode: "20001",
      fullName: "Linked Student B1",
      status: "active",
      archivedAtMs: null,
      isAppAccessEnabled: true,
      firebaseUid: "student-uid-b1",
    });
    await setDoc(tenantDoc(db, "expired-institute", "students", "expired-student"), {
      instituteId: "expired-institute",
      fullName: "Expired Student",
      status: "active",
    });
    await setDoc(tenantDoc(db, OWNER_A, "students", "legacy-credential-student"), {
      instituteId: OWNER_A,
      studentCode: "10003",
      fullName: "Legacy Credential Student",
      status: "active",
      archivedAtMs: null,
      isAppAccessEnabled: true,
      firebaseUid: "legacy-student-uid",
      studentPasswordHash: "must-never-be-readable",
      appAccessEmail: "legacy-student@s.batchfee.app",
    });
    await setDoc(tenantDoc(db, OWNER_A, "fees", "fee-a"), {
      instituteId: OWNER_A,
      studentId: "student-a",
      totalAmount: 1000,
    });
    await setDoc(tenantDoc(db, OWNER_B, "fees", "fee-b"), {
      instituteId: OWNER_B,
      studentId: "student-b",
      totalAmount: 2000,
    });
    await setDoc(tenantDoc(db, OWNER_A, "fees", "fee-a1"), {
      instituteId: OWNER_A,
      studentId: "student-doc-a1",
      totalAmount: 1500,
    });
    await setDoc(tenantDoc(db, OWNER_A, "fees", "fee-a2"), {
      instituteId: OWNER_A,
      studentId: "student-doc-a2",
      totalAmount: 2500,
    });
    await setDoc(tenantDoc(db, OWNER_A, "payments", "payment-a1"), {
      instituteId: OWNER_A,
      feeId: "fee-a1",
      studentId: "student-doc-a1",
      amount: 500,
      status: "completed",
    });
    await setDoc(tenantDoc(db, OWNER_A, "receipts", "receipt-a1"), {
      instituteId: OWNER_A,
      paymentId: "payment-a1",
      feeId: "fee-a1",
      studentId: "student-doc-a1",
      receiptNumber: "REC-0000000001",
    });
    await setDoc(tenantDoc(db, OWNER_A, "subscription_receipts", "subscription-receipt-a1"), {
      instituteId: OWNER_A,
      amountPaid: 999,
      status: "approved",
    });
    await setDoc(tenantDoc(db, OWNER_B, "subscription_receipts", "subscription-receipt-b1"), {
      instituteId: OWNER_B,
      amountPaid: 1999,
      status: "approved",
    });
    await setDoc(tenantDoc(db, OWNER_A, "payment_reversals", "reversal-a1"), {
      instituteId: OWNER_A,
      paymentId: "payment-a1",
      feeId: "fee-a1",
      studentId: "student-doc-a1",
      amount: 500,
    });
    await setDoc(tenantDoc(db, OWNER_A, "fee_adjustments", "adjustment-a1"), {
      instituteId: OWNER_A,
      feeId: "fee-a1",
      studentId: "student-doc-a1",
    });
    await setDoc(tenantDoc(db, OWNER_A, "financial_operations", "operation-a1"), {
      instituteId: OWNER_A,
      actorUid: OWNER_A,
    });
    await setDoc(tenantDoc(db, OWNER_A, "ledger_internal", "receipt_sequence"), {
      lastValue: 1,
    });
    await setDoc(doc(db, "student_login_mappings", "10001"), {
      instituteId: OWNER_A,
      studentDocId: "student-doc-a1",
      appAccessEmail: "legacy@s.batchfee.app",
    });
    await setDoc(doc(db, "student_auth_logins", "private-login-key"), {
      instituteId: OWNER_A,
      studentId: "student-doc-a1",
      firebaseUid: "student-uid-a1",
      passwordHash: "private",
    });
    await setDoc(doc(db, "student_auth_accounts", "student-uid-a1"), {
      instituteId: OWNER_A,
      studentId: "student-doc-a1",
      loginKey: "private-login-key",
    });
    await setDoc(doc(db, "student_auth_attempts", "private-login-key"), {
      failedAttempts: 1,
    });
    await setDoc(doc(db, "registrations", OWNER_A, "pending", "registration-a"), {
      instituteId: OWNER_A,
      status: "pending",
      fullName: "Applicant A",
    });
    await setDoc(doc(db, "registrations", OWNER_B, "pending", "registration-b"), {
      instituteId: OWNER_B,
      status: "pending",
      fullName: "Applicant B",
    });
    await setDoc(doc(db, "public_registration_profiles", "institute-b"), {
      instituteId: OWNER_B,
      instituteName: "Institute B",
      slug: "institute-b",
      phone: "+8801700000000",
      profilePhotoUri: null,
      updatedAtMs: Date.now(),
    });

    // This document proves an institute-owned role field is no longer trusted.
    await setDoc(instituteRef(db, "forged-root-admin"), {
      instituteName: "Forged Admin",
      role: "SuperAdmin",
      isActive: true,
    });
  });
}

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync("firestore.rules", "utf8"),
    },
  });
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await seedBaseData();
});

after(async () => {
  await testEnv.cleanup();
});

describe("P0-01 tenant isolation", { concurrency: false }, () => {
  test("owner can read own institute but cannot read or list another institute", async () => {
    const db = authDb(OWNER_A, "owner-a@example.test");
    await assertSucceeds(getDoc(instituteRef(db, OWNER_A)));
    await assertFails(getDoc(instituteRef(db, OWNER_B)));
    await assertFails(getDocs(collection(db, "institutes")));
  });

  test("active staff can read only its own institute and permitted tenant data", async () => {
    const db = authDb("staff-view-a");
    await assertSucceeds(getDoc(instituteRef(db, OWNER_A)));
    await assertFails(getDoc(instituteRef(db, OWNER_B)));
    await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "students", "student-a")));
    await assertFails(getDoc(tenantDoc(db, OWNER_B, "students", "student-b")));
    await assertFails(getDoc(tenantDoc(db, OWNER_A, "fees", "fee-a")));
  });

  test("registration records are tenant-isolated", async () => {
    const ownerDb = authDb(OWNER_A);
    await assertSucceeds(getDoc(doc(ownerDb, "registrations", OWNER_A, "pending", "registration-a")));
    await assertFails(getDoc(doc(ownerDb, "registrations", OWNER_B, "pending", "registration-b")));
  });

  test("owner cannot overwrite another institute public profile", async () => {
    const db = authDb(OWNER_A);
    await assertFails(updateDoc(doc(db, "public_registration_profiles", "institute-b"), {
      instituteId: OWNER_A,
      instituteName: "Hijacked",
    }));
  });

  test("public registration visitors can read only the explicit public profile", async () => {
    const anonymousDb = testEnv.unauthenticatedContext().firestore();
    await assertSucceeds(getDoc(doc(anonymousDb, "public_registration_profiles", "institute-b")));
    await assertFails(getDoc(instituteRef(anonymousDb, OWNER_B)));
    await assertFails(getDocs(collection(anonymousDb, "public_registration_profiles")));
    await assertFails(setDoc(doc(anonymousDb, "registrations", OWNER_B, "pending", "forged"), {
      instituteId: OWNER_B,
      fullName: "Forged registration",
      phone: "+8801712345678",
      status: "pending",
    }));
  });

  test("an owner cannot publish private fields in a public registration profile", async () => {
    const db = authDb(OWNER_A);
    await assertFails(setDoc(doc(db, "public_registration_profiles", "private-leak"), {
      instituteId: OWNER_A,
      instituteName: "Institute A",
      slug: "private-leak",
      phone: "+8801700000000",
      profilePhotoUri: null,
      updatedAtMs: Date.now(),
      securityPin: "must-not-be-public",
    }));
  });

  test("a forged SuperAdmin role in an institute document grants no authority", async () => {
    const db = authDb("forged-root-admin");
    await assertFails(getDoc(instituteRef(db, OWNER_A)));
    await assertFails(getDocs(collection(db, "institutes")));
    await assertFails(updateDoc(instituteRef(db, OWNER_B), { isActive: false }));
    await assertFails(setDoc(doc(db, "app_users", "forged-root-admin"), {
      role: "SuperAdmin",
      status: "active",
    }));
  });
});

describe("P0-02 owner and staff security boundary", { concurrency: false }, () => {
  test("owner can update profile but cannot alter server-owned counters or entitlement fields", async () => {
    const db = authDb(OWNER_A, "owner-a@example.test");
    await assertSucceeds(updateDoc(instituteRef(db, OWNER_A), {
      instituteName: "Institute A Updated",
      phone: "+8801711111111",
      lastActiveAt: Date.now(),
    }));

    for (const protectedChange of [
      { isActive: false },
      { currentPlanId: "plan_institute" },
      { subscriptionStatus: "cancelled" },
      { trialEndDate: Date.now() + 31_536_000_000 },
      { currentPeriodEndMs: Date.now() + 31_536_000_000 },
      { studentLimit: 999999 },
      { staffLimit: 999999 },
      { studentCount: 2 },
      { staffCount: 2 },
      { batchCount: 2 },
      { securityPin: "0000" },
      { role: "SuperAdmin" },
    ]) {
      await assertFails(updateDoc(instituteRef(db, OWNER_A), protectedChange));
    }
    await assertFails(deleteDoc(instituteRef(db, OWNER_A)));
  });

  test("owner can create only its own validated initial trial document", async () => {
    const uid = "new-owner";
    const email = "new-owner@example.test";
    const db = authDb(uid, email);
    await assertSucceeds(setDoc(instituteRef(db, uid), validNewInstitute(email)));
    await assertFails(setDoc(instituteRef(db, "some-other-owner"), validNewInstitute(email)));

    const legacyUid = "legacy-limit-owner";
    const legacyEmail = "legacy-limit-owner@example.test";
    const legacyTrial = validNewInstitute(legacyEmail);
    legacyTrial.studentLimit = 50;
    await assertSucceeds(setDoc(instituteRef(authDb(legacyUid, legacyEmail), legacyUid), legacyTrial));

    const invalidUid = "invalid-new-owner";
    const invalidEmail = "invalid-new-owner@example.test";
    const invalidDb = authDb(invalidUid, invalidEmail);
    const invalid = validNewInstitute(invalidEmail);
    invalid.trialEndDate += 31_536_000_000;
    await assertFails(setDoc(instituteRef(invalidDb, invalidUid), invalid));

    const invalidLimit = validNewInstitute("invalid-limit@example.test");
    invalidLimit.studentLimit = -1;
    await assertFails(setDoc(instituteRef(authDb("invalid-limit", "invalid-limit@example.test"), "invalid-limit"), invalidLimit));
  });

  test("new owner can create the app's phone-bound free-trial receipt", async () => {
    const uid = "new-owner-with-contact";
    const email = "new-owner-with-contact@example.test";
    const contact = "+8801518657896";
    const createdAt = Date.now();
    const db = authDb(uid, email);
    const institute = validNewInstitute(email, createdAt);
    institute.phone = contact;
    institute.whatsappNumber = contact;

    await assertSucceeds(setDoc(instituteRef(db, uid), institute));

    const receipt = {
      receiptNumber: `TRIAL-${createdAt}`,
      instituteId: uid,
      instituteName: institute.instituteName,
      ownerName: institute.ownerName,
      ownerEmail: email,
      ownerPhone: contact,
      instituteCode: "",
      instituteAddress: "",
      planId: "plan_free_trial",
      planName: "Free Trial",
      durationMonths: 1,
      amountPaid: 0,
      paymentMethod: "free_trial",
      transactionLast4: "",
      startDateMs: createdAt,
      endDateMs: institute.currentPeriodEndMs,
      approvedAt: createdAt,
      status: "approved",
    };

    await assertSucceeds(setDoc(
      tenantDoc(db, uid, "receipts", receipt.receiptNumber),
      receipt,
    ));
    await assertFails(setDoc(
      tenantDoc(db, uid, "receipts", `TRIAL-${createdAt + 1}`),
      { ...receipt, receiptNumber: `TRIAL-${createdAt + 1}`, ownerPhone: "+8801999999999" },
    ));
  });

  test("active staff access is permission-scoped and token matching is exact", async () => {
    const viewDb = authDb("staff-view-a");
    await assertSucceeds(getDoc(tenantDoc(viewDb, OWNER_A, "students", "student-a")));
    await assertFails(setDoc(tenantDoc(viewDb, OWNER_A, "students", "new-student"), {
      instituteId: OWNER_A,
      fullName: "Denied",
    }));

    const collectDb = authDb("staff-collect-a");
    await assertSucceeds(getDoc(tenantDoc(collectDb, OWNER_A, "fees", "fee-a")));
    await assertFails(setDoc(tenantDoc(collectDb, OWNER_A, "fees", "new-fee"), {
      instituteId: OWNER_A,
      studentId: "student-a",
      totalAmount: 500,
    }));
    await assertFails(getDoc(tenantDoc(collectDb, OWNER_A, "staffs", "staff-view-a")));

    const fakeTokenDb = authDb("staff-fake-token-a");
    await assertFails(getDoc(tenantDoc(fakeTokenDb, OWNER_A, "students", "student-a")));
  });

  test("owners cannot bypass trusted quota creation with direct student, batch, or staff writes", async () => {
    const db = authDb(OWNER_A);
    await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "batches", "batch-a")));
    await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "batch_students", "enrollment-a")));
    await assertFails(setDoc(tenantDoc(db, OWNER_A, "students", "direct-student"), {
      instituteId: OWNER_A, studentCode: "DIRECT-1", fullName: "Denied", status: "active",
    }));
    await assertFails(setDoc(tenantDoc(db, OWNER_A, "batches", "direct-batch"), {
      instituteId: OWNER_A, batchCode: "DIRECT-1", name: "Denied", status: "active",
    }));
    await assertFails(setDoc(tenantDoc(db, OWNER_A, "staffs", "direct-staff"), {
      instituteId: OWNER_A, staffCode: "DIRECT-1", fullName: "Denied", status: "active",
    }));
  });

  test("inactive, blocked, archived, and institute-blocked staff lose tenant access", async () => {
    for (const staffId of ["staff-inactive-a", "staff-blocked-a", "staff-archived-a"]) {
      const db = authDb(staffId);
      await assertFails(getDoc(instituteRef(db, OWNER_A)));
      await assertFails(getDoc(tenantDoc(db, OWNER_A, "students", "student-a")));
      await assertFails(getDoc(tenantDoc(db, OWNER_A, "staffs", staffId)));
    }

    const blockedInstituteDb = authDb("staff-active-blocked-institute");
    await assertFails(getDoc(instituteRef(blockedInstituteDb, "blocked-institute")));
  });

  test("an expired subscription permits billing context only, not protected tenant data", async () => {
    const ownerDb = authDb("expired-institute", "expired-owner@example.test");
    await assertSucceeds(getDoc(instituteRef(ownerDb, "expired-institute")));
    await assertFails(getDoc(tenantDoc(ownerDb, "expired-institute", "students", "expired-student")));
    await assertFails(updateDoc(instituteRef(ownerDb, "expired-institute"), { phone: "+8801700000000" }));

    const studentDb = authDb(
      "expired-student-uid",
      "expired-student@example.test",
      {
        student: true,
        instituteId: "expired-institute",
        studentId: "expired-student",
        studentSessionExpiresAt: Date.now() + 3_600_000,
      },
    );
    await assertFails(getDoc(tenantDoc(studentDb, "expired-institute", "students", "expired-student")));
  });

  test("staff may update activity only and cannot manipulate protected institute fields", async () => {
    const db = authDb("staff-view-a");
    await assertSucceeds(updateDoc(instituteRef(db, OWNER_A), { lastActiveAt: Date.now() }));
    await assertFails(updateDoc(instituteRef(db, OWNER_A), { currentPlanId: "plan_institute" }));
    await assertFails(updateDoc(instituteRef(db, OWNER_A), { studentLimit: 999999 }));
    await assertFails(updateDoc(instituteRef(db, OWNER_A), { securityPin: "0000" }));
  });

  test("subscription requests are server-authoritative and reject every raw client write", async () => {
    const db = authDb(OWNER_A);
    const valid = {
      instituteId: OWNER_A,
      instituteName: "Institute A",
      ownerName: "Owner A",
      institutePhone: "",
      requestedPlanId: "plan_growth",
      durationMonths: 1,
      amountPaid: 999,
      transactionLast4: "1234",
      paymentMethod: "manual",
      status: "pending",
      requestSentAt: Date.now(),
      reviewedBy: "",
      reviewedAt: 0,
      reviewerNote: "",
    };
    await assertFails(setDoc(doc(db, "subscriptionRequests", "request-own"), valid));
    await assertFails(setDoc(doc(db, "subscriptionRequests", "request-other"), {
      ...valid,
      instituteId: OWNER_B,
    }));
    await assertFails(setDoc(doc(db, "subscriptionRequests", "request-forged-approval"), {
      ...valid,
      status: "approved",
      reviewedBy: OWNER_A,
      reviewedAt: Date.now(),
    }));
  });

  test("trusted SuperAdmin can read globally but cannot mutate subscription controls directly", async () => {
    const db = authDb(ADMIN);
    await assertSucceeds(getDocs(collection(db, "institutes")));
    await assertSucceeds(updateDoc(instituteRef(db, OWNER_A), { phone: "+8801799999999" }));
    await assertFails(updateDoc(instituteRef(db, OWNER_A), {
      currentPlanId: "plan_pro",
      currentPeriodEndMs: Date.now() + 31_536_000_000,
      studentLimit: 1500,
    }));
  });

  test("platform reader can query all subscription receipts without exposing other tenants", async () => {
    const adminDb = authDb(ADMIN);
    const allReceipts = await assertSucceeds(
      getDocs(collectionGroup(adminDb, "subscription_receipts")),
    );
    assert.equal(allReceipts.size, 2);

    const ownerDb = authDb(OWNER_A);
    await assertSucceeds(
      getDoc(tenantDoc(ownerDb, OWNER_A, "subscription_receipts", "subscription-receipt-a1")),
    );
    await assertFails(
      getDoc(tenantDoc(ownerDb, OWNER_B, "subscription_receipts", "subscription-receipt-b1")),
    );
    await assertFails(getDocs(collectionGroup(ownerDb, "subscription_receipts")));
  });

  test("managed InstituteAdmin is same-tenant only and cannot edit entitlement fields", async () => {
    const db = authDb("institute-admin-a");
    await assertSucceeds(getDoc(instituteRef(db, OWNER_A)));
    await assertFails(getDoc(instituteRef(db, OWNER_B)));
    await assertSucceeds(updateDoc(instituteRef(db, OWNER_A), { phone: "+8801722222222" }));
    await assertFails(updateDoc(instituteRef(db, OWNER_A), { currentPlanId: "plan_institute" }));

    const inactiveDb = authDb("inactive-institute-admin-a");
    await assertFails(getDoc(instituteRef(inactiveDb, OWNER_A)));
  });
});

describe("P0-03 student authentication boundary", { concurrency: false }, () => {
  function studentClaims(instituteId, studentId, expiresAt = Date.now() + 3_600_000) {
    return {
      student: true,
      instituteId,
      studentId,
      studentSessionExpiresAt: expiresAt,
    };
  }

  test("legacy and replacement login indexes are inaccessible to every client", async () => {
    const anonymousDb = testEnv.unauthenticatedContext().firestore();
    const ownerDb = authDb(OWNER_A);
    const studentDb = authDb(
      "student-uid-a1",
      "student-uid-a1@example.test",
      studentClaims(OWNER_A, "student-doc-a1"),
    );

    for (const db of [anonymousDb, ownerDb, studentDb]) {
      await assertFails(getDoc(doc(db, "student_login_mappings", "10001")));
      await assertFails(getDoc(doc(db, "student_auth_logins", "private-login-key")));
      await assertFails(setDoc(doc(db, "student_login_mappings", "tampered"), {
        instituteId: OWNER_A,
      }));
    }
  });

  test("linked student can read only its own private records", async () => {
    const db = authDb(
      "student-uid-a1",
      "student-uid-a1@example.test",
      studentClaims(OWNER_A, "student-doc-a1"),
    );
    await assertSucceeds(getDoc(instituteRef(db, OWNER_A)));
    await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "students", "student-doc-a1")));
    await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "fees", "fee-a1")));
    await assertFails(getDoc(tenantDoc(db, OWNER_A, "students", "student-doc-a2")));
    await assertFails(getDoc(tenantDoc(db, OWNER_A, "fees", "fee-a2")));
    await assertFails(getDoc(instituteRef(db, OWNER_B)));
    await assertFails(getDoc(tenantDoc(db, OWNER_B, "students", "student-doc-b1")));
  });

  test("forged, expired, disabled, and cross-institute student links fail closed", async () => {
    const forgedUidDb = authDb(
      "attacker-uid",
      "attacker@example.test",
      studentClaims(OWNER_A, "student-doc-a1"),
    );
    await assertFails(getDoc(tenantDoc(forgedUidDb, OWNER_A, "students", "student-doc-a1")));

    const expiredDb = authDb(
      "student-uid-a1",
      "student-uid-a1@example.test",
      studentClaims(OWNER_A, "student-doc-a1", Date.now() - 1_000),
    );
    await assertFails(getDoc(tenantDoc(expiredDb, OWNER_A, "students", "student-doc-a1")));

    const wrongInstituteDb = authDb(
      "student-uid-a1",
      "student-uid-a1@example.test",
      studentClaims(OWNER_B, "student-doc-a1"),
    );
    await assertFails(getDoc(tenantDoc(wrongInstituteDb, OWNER_A, "students", "student-doc-a1")));

    await testEnv.withSecurityRulesDisabled(async (context) => {
      await updateDoc(tenantDoc(context.firestore(), OWNER_A, "students", "student-doc-a1"), {
        isAppAccessEnabled: false,
      });
    });
    const disabledDb = authDb(
      "student-uid-a1",
      "student-uid-a1@example.test",
      studentClaims(OWNER_A, "student-doc-a1"),
    );
    await assertFails(getDoc(tenantDoc(disabledDb, OWNER_A, "students", "student-doc-a1")));
  });

  test("owner and staff operational access cannot alter UID or credential fields", async () => {
    const ownerDb = authDb(OWNER_A);
    await assertSucceeds(updateDoc(tenantDoc(ownerDb, OWNER_A, "students", "student-doc-a1"), {
      fullName: "Updated safely",
    }));
    for (const protectedChange of [
      { firebaseUid: OWNER_A },
      { isAppAccessEnabled: false },
      { studentPasswordHash: "client-hash" },
      { appAccessEmail: "client-controlled@s.batchfee.app" },
    ]) {
      await assertFails(updateDoc(
        tenantDoc(ownerDb, OWNER_A, "students", "student-doc-a1"),
        protectedChange,
      ));
    }
  });

  test("student self-update stays narrow and cannot change its identity link", async () => {
    const db = authDb(
      "student-uid-a1",
      "student-uid-a1@example.test",
      studentClaims(OWNER_A, "student-doc-a1"),
    );
    await assertSucceeds(updateDoc(tenantDoc(db, OWNER_A, "students", "student-doc-a1"), {
      phone: "+8801700000001",
      updatedAtMs: Date.now(),
    }));
    await assertFails(updateDoc(tenantDoc(db, OWNER_A, "students", "student-doc-a1"), {
      firebaseUid: "attacker-uid",
    }));
    await assertFails(updateDoc(tenantDoc(db, OWNER_A, "students", "student-doc-a2"), {
      phone: "+8801700000002",
    }));
  });
});

describe("P0-04 student credential privacy", { concurrency: false }, () => {
  function studentClaims(instituteId, studentId) {
    return {
      student: true,
      instituteId,
      studentId,
      studentSessionExpiresAt: Date.now() + 3_600_000,
    };
  }

  test("legacy credential-bearing student documents fail closed for every client role", async () => {
    const legacyRefFor = (db) => tenantDoc(
      db,
      OWNER_A,
      "students",
      "legacy-credential-student",
    );
    const databases = [
      testEnv.unauthenticatedContext().firestore(),
      authDb(OWNER_A),
      authDb("staff-view-a"),
      authDb(ADMIN),
      authDb(
        "legacy-student-uid",
        "legacy-student-uid@example.test",
        studentClaims(OWNER_A, "legacy-credential-student"),
      ),
    ];

    for (const db of databases) {
      await assertFails(getDoc(legacyRefFor(db)));
    }
    await assertSucceeds(getDoc(tenantDoc(
      authDb(OWNER_A),
      OWNER_A,
      "students",
      "student-doc-a1",
    )));
  });

  test("owner and managing staff cannot create or preserve credential fields", async () => {
    for (const db of [authDb(OWNER_A), authDb("staff-manage-a")]) {
      await assertFails(setDoc(tenantDoc(db, OWNER_A, "students", `dirty-${Math.random()}`), {
        instituteId: OWNER_A,
        studentCode: "blocked",
        fullName: "Blocked",
        passwordVerifier: "client-controlled",
      }));
      await assertFails(updateDoc(
        tenantDoc(db, OWNER_A, "students", "legacy-credential-student"),
        { fullName: "Credential retained" },
      ));
    }
  });

  test("all credential and login metadata collections are backend-only", async () => {
    for (const db of [authDb(OWNER_A), authDb("staff-view-a"), authDb(ADMIN)]) {
      await assertFails(getDoc(doc(db, "student_login_mappings", "10001")));
      await assertFails(getDoc(doc(db, "student_auth_logins", "private-login-key")));
      await assertFails(getDoc(doc(db, "student_auth_accounts", "student-uid-a1")));
      await assertFails(getDoc(doc(db, "student_auth_attempts", "private-login-key")));
    }
  });
});

describe("P0-05 financial ledger integrity boundary", { concurrency: false }, () => {
  function linkedStudentDb() {
    return authDb("student-uid-a1", "student-uid-a1@example.test", {
      student: true,
      instituteId: OWNER_A,
      studentId: "student-doc-a1",
      studentSessionExpiresAt: Date.now() + 3_600_000,
    });
  }

  test("authorized users retain scoped read access to immutable financial records", async () => {
    for (const db of [authDb(OWNER_A), authDb("staff-collect-a"), linkedStudentDb()]) {
      await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "fees", "fee-a1")));
      await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "payments", "payment-a1")));
      await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "receipts", "receipt-a1")));
      await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "payment_reversals", "reversal-a1")));
      await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "fee_adjustments", "adjustment-a1")));
    }
    await assertFails(getDoc(tenantDoc(linkedStudentDb(), OWNER_A, "fees", "fee-a2")));
  });

  test("owner, collecting staff, and superadmin cannot directly mutate ledger records", async () => {
    for (const db of [authDb(OWNER_A), authDb("staff-collect-a"), authDb(ADMIN)]) {
      await assertFails(updateDoc(tenantDoc(db, OWNER_A, "fees", "fee-a1"), {
        paidAmount: 999999,
      }));
      await assertFails(deleteDoc(tenantDoc(db, OWNER_A, "payments", "payment-a1")));
      await assertFails(deleteDoc(tenantDoc(db, OWNER_A, "receipts", "receipt-a1")));
      await assertFails(setDoc(tenantDoc(db, OWNER_A, "payment_reversals", "forged"), {
        instituteId: OWNER_A,
        studentId: "student-doc-a1",
        paymentId: "payment-a1",
      }));
      await assertFails(setDoc(tenantDoc(db, OWNER_A, "fee_adjustments", "forged"), {
        instituteId: OWNER_A,
        studentId: "student-doc-a1",
        feeId: "fee-a1",
      }));
    }
  });

  test("operation idempotency and receipt sequence documents are backend-only", async () => {
    for (const db of [authDb(OWNER_A), authDb("staff-collect-a"), authDb(ADMIN), linkedStudentDb()]) {
      await assertFails(getDoc(tenantDoc(db, OWNER_A, "financial_operations", "operation-a1")));
      await assertFails(getDoc(tenantDoc(db, OWNER_A, "ledger_internal", "receipt_sequence")));
      await assertFails(setDoc(tenantDoc(db, OWNER_A, "financial_operations", "forged"), {
        actorUid: OWNER_A,
      }));
      await assertFails(setDoc(tenantDoc(db, OWNER_A, "ledger_internal", "receipt_sequence"), {
        lastValue: 0,
      }));
    }
  });
});

describe("P0-06 safe permanent deletion boundary", { concurrency: false }, () => {
  test("student, batch, institute, and principal records cannot be hard-deleted by clients", async () => {
    for (const db of [authDb(OWNER_A), authDb("staff-manage-a"), authDb(ADMIN)]) {
      await assertFails(deleteDoc(tenantDoc(db, OWNER_A, "students", "student-doc-a1")));
      await assertFails(deleteDoc(tenantDoc(db, OWNER_A, "batches", "batch-a")));
    }
    await assertFails(deleteDoc(instituteRef(authDb(ADMIN), OWNER_A)));
    await assertFails(deleteDoc(doc(authDb(ADMIN), "app_users", "institute-admin-a")));
  });

  test("deletion state and audit documents are backend-written and client read-only", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const db = context.firestore();
      await setDoc(tenantDoc(db, OWNER_A, "deletion_operations", "operation-delete-a"), {
        instituteId: OWNER_A,
        entityType: "student",
        entityId: "student-doc-a1",
        action: "archive",
      });
      await setDoc(tenantDoc(db, OWNER_A, "deletion_states", "student_student-doc-a1"), {
        instituteId: OWNER_A,
        entityType: "student",
        entityId: "student-doc-a1",
        active: true,
      });
      await setDoc(tenantDoc(db, OWNER_A, "deletion_audit", "operation-delete-a"), {
        instituteId: OWNER_A,
        entityType: "student",
        entityId: "student-doc-a1",
        action: "archive",
      });
    });

    for (const db of [authDb(OWNER_A), authDb(ADMIN)]) {
      await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "deletion_operations", "operation-delete-a")));
      await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "deletion_states", "student_student-doc-a1")));
      await assertSucceeds(getDoc(tenantDoc(db, OWNER_A, "deletion_audit", "operation-delete-a")));
      await assertFails(setDoc(tenantDoc(db, OWNER_A, "deletion_audit", "forged"), {
        action: "archive",
      }));
      await assertFails(deleteDoc(tenantDoc(db, OWNER_A, "deletion_operations", "operation-delete-a")));
    }
  });

  test("archived institute fails closed while its canonical tree and ledger stay readable to SuperAdmin", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await updateDoc(instituteRef(context.firestore(), OWNER_A), {
        isActive: false,
        status: "archived",
        deletionState: "retained",
        archivedAtMs: Date.now(),
      });
    });

    await assertFails(getDoc(instituteRef(authDb(OWNER_A), OWNER_A)));
    await assertFails(getDoc(tenantDoc(authDb("staff-view-a"), OWNER_A, "students", "student-a")));
    await assertFails(getDoc(tenantDoc(authDb(
      "student-uid-a1",
      "student@example.test",
      {
        student: true,
        instituteId: OWNER_A,
        studentId: "student-doc-a1",
        studentSessionExpiresAt: Date.now() + 3_600_000,
      },
    ), OWNER_A, "fees", "fee-a1")));
    await assertSucceeds(getDoc(instituteRef(authDb(ADMIN), OWNER_A)));
    await assertSucceeds(getDoc(tenantDoc(authDb(ADMIN), OWNER_A, "payments", "payment-a1")));
  });

  test("legacy trash copies cannot be mutated or permanently deleted by a client", async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), "institutes_trash", "legacy-trash"), {
        instituteName: "Legacy retained institute",
      });
    });
    const db = authDb(ADMIN);
    await assertSucceeds(getDoc(doc(db, "institutes_trash", "legacy-trash")));
    await assertFails(deleteDoc(doc(db, "institutes_trash", "legacy-trash")));
    await assertFails(updateDoc(doc(db, "institutes_trash", "legacy-trash"), { purged: true }));
  });
});

describe("P0-07 media metadata boundary", { concurrency: false }, () => {
  test("media ownership, upload operations, and audit metadata are backend-only", async () => {
    const ownerDb = authDb(OWNER_A);
    const adminDb = authDb(ADMIN);
    const staffDb = authDb("staff-manage-a");
    const collections = ["media_assets", "media_upload_operations", "media_audit"];

    for (const collectionName of collections) {
      const reference = tenantDoc(ownerDb, OWNER_A, collectionName, "media-1");
      await assertFails(getDoc(reference));
      await assertFails(setDoc(reference, { instituteId: OWNER_A, cloudinaryPublicId: "private/id" }));
      await assertFails(getDoc(tenantDoc(adminDb, OWNER_A, collectionName, "media-1")));
      await assertFails(getDoc(tenantDoc(staffDb, OWNER_A, collectionName, "media-1")));
    }
  });

  test("another institute cannot inspect or forge media metadata", async () => {
    const otherOwnerDb = authDb(OWNER_B);
    await assertFails(getDoc(tenantDoc(otherOwnerDb, OWNER_A, "media_assets", "media-1")));
    await assertFails(setDoc(
      tenantDoc(otherOwnerDb, OWNER_A, "media_assets", "media-1"),
      { instituteId: OWNER_B, reference: "batchfee-media://v1/owner-b/forged" },
    ));
  });
});
