// Firestore Security Rules tests (NEXORA_PLAN.md §9.2). Run with: npm test (starts the Firestore emulator).
// Boundary values mirror app/src/test/.../EligibilityMatchTest.java (parity requirement).
const { describe, it, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, query, where, getDocs, writeBatch,
  serverTimestamp, Timestamp,
} = require('firebase/firestore');

let env;
const DAY = 24 * 60 * 60 * 1000;
const future = () => Timestamp.fromMillis(Date.now() + 2 * DAY);
const past = () => Timestamp.fromMillis(Date.now() - DAY);

const PROFILES = {
  aisha: { name: 'Aisha Khan', rollNumber: '21CSAI001', branch: 'CSE_AI', cgpa: 8.4, activeBacklogs: 0, graduationYear: 2027, academicVerified: true },
  exact: { name: 'Eshan Rao', rollNumber: '21CS007', branch: 'CSE', cgpa: 7.0, activeBacklogs: 0, graduationYear: 2027, academicVerified: true },
  rahul: { name: 'Rahul Verma', rollNumber: '21CS042', branch: 'CSE', cgpa: 6.99, activeBacklogs: 0, graduationYear: 2027, academicVerified: true },
  meera: { name: 'Meera Nair', rollNumber: '21ME015', branch: 'ME', cgpa: 8.9, activeBacklogs: 0, graduationYear: 2027, academicVerified: true },
  kiran: { name: 'Kiran Das', rollNumber: '21CS099', branch: 'CSE', cgpa: 8.1, activeBacklogs: 0, graduationYear: 2027, academicVerified: false },
  backlog: { name: 'Bala S', rollNumber: '21CS111', branch: 'CSE', cgpa: 8.0, activeBacklogs: 1, graduationYear: 2027, academicVerified: true },
};

function driveDoc(overrides = {}) {
  return {
    companyId: 'acme', companyName: 'Acme', title: 'SDE Intern', roleCategory: 'SDE', jobType: 'INTERNSHIP',
    location: 'Bengaluru', packageLpa: 12, description: 'Build things',
    criteria: { minCgpa: 7.0, eligibleBranches: ['CSE', 'CSE_AI'], maxActiveBacklogs: 0, graduationYears: [2027] },
    requiredSkills: ['java'], prepFocus: ['CODING'],
    stages: [{ name: 'Application Submitted', type: 'SUBMISSION' }, { name: 'Technical Interview', type: 'INTERVIEW' },
      { name: 'Final Outcome', type: 'OUTCOME' }],
    deadline: future(), status: 'OPEN', applicantCount: 0, createdBy: 'officer',
    ...overrides,
  };
}

/** A valid application exactly as the Android client writes it. */
function appDoc(uid, driveId = 'd1', overrides = {}) {
  const p = PROFILES[uid];
  return {
    driveId, studentId: uid, driveTitle: 'SDE Intern', companyName: 'Acme', studentName: p.name, studentRoll: p.rollNumber,
    profileSnapshot: { cgpa: p.cgpa, branch: p.branch, activeBacklogs: p.activeBacklogs, graduationYear: p.graduationYear },
    matchScore: 80, status: 'SUBMITTED', currentStage: 0, stageHistory: [],
    appliedAt: serverTimestamp(), updatedAt: serverTimestamp(),
    ...overrides,
  };
}

const db = (uid) => env.authenticatedContext(uid, { email: `${uid}@nexora.test` }).firestore();
const officer = () => db('officer');
const anon = () => env.unauthenticatedContext().firestore();

before(async () => {
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080').split(':');
  env = await initializeTestEnvironment({
    projectId: 'demo-nexora-rules',
    firestore: { rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'), host, port: Number(port) },
  });
});

after(async () => { await env.cleanup(); });

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const f = ctx.firestore();
    await setDoc(doc(f, 'users/officer'), { uid: 'officer', name: 'Placement Officer', email: 'officer@nexora.test', role: 'OFFICER' });
    for (const [uid, p] of Object.entries(PROFILES)) {
      await setDoc(doc(f, `users/${uid}`), { uid, name: p.name, email: `${uid}@nexora.test`, role: 'STUDENT' });
      await setDoc(doc(f, `studentProfiles/${uid}`), { ...p, email: `${uid}@nexora.test`, skills: [], preferredRoles: [], certifications: [] });
    }
    await setDoc(doc(f, 'placementDrives/d1'), driveDoc());
    await setDoc(doc(f, 'placementDrives/dClosed'), driveDoc({ status: 'CLOSED' }));
    await setDoc(doc(f, 'placementDrives/dExpired'), driveDoc({ deadline: past() }));
    await setDoc(doc(f, 'placementDrives/dDraft'), driveDoc({ status: 'DRAFT' }));
    await setDoc(doc(f, 'placementDrives/dBacklog'), driveDoc({ criteria: { minCgpa: 7.0, eligibleBranches: ['CSE'], maxActiveBacklogs: 1, graduationYears: [2027] } }));
    await setDoc(doc(f, 'applications/d1_aisha'), appDoc('aisha', 'd1', { appliedAt: Timestamp.now(), updatedAt: Timestamp.now() }));
    await setDoc(doc(f, 'notifications/all1'), { title: 'Hello', body: 'All', audience: 'ALL', targetUid: null, createdBy: 'officer', createdAt: Timestamp.now() });
    await setDoc(doc(f, 'notifications/forKiran'), { title: 'Update', body: 'For Kiran', audience: 'STUDENT', targetUid: 'kiran', createdBy: 'officer', createdAt: Timestamp.now() });
    await setDoc(doc(f, 'readinessProgress/aisha/activities/a1'), { type: 'CODING', title: 'LC', scorePercent: 70, rating: null, durationMin: 60, date: Timestamp.now(), createdAt: Timestamp.now() });
  });
});

// ------------------------------------------------------------------ users / roles
describe('users and roles', () => {
  const newUser = (role, extra = {}) => ({ uid: 'newbie', name: 'New Student', email: 'newbie@nexora.test', role, createdAt: serverTimestamp(), ...extra });

  it('registration creates a STUDENT', async () => {
    await assertSucceeds(setDoc(doc(db('newbie'), 'users/newbie'), newUser('STUDENT')));
  });
  it('can register as OFFICER (open officer sign-up) but not with any other role', async () => {
    await assertSucceeds(setDoc(doc(db('newbie'), 'users/newbie'), newUser('OFFICER')));
    await assertFails(setDoc(doc(db('other'), 'users/other'), { ...newUser('ADMIN'), uid: 'other', email: 'other@nexora.test' }));
  });
  it('an officer cannot also create a student profile to apply to drives', async () => {
    await env.withSecurityRulesDisabled((ctx) => setDoc(doc(ctx.firestore(), 'users/off2'),
      { uid: 'off2', name: 'Officer Two', email: 'off2@nexora.test', role: 'OFFICER' }));
    await assertFails(setDoc(doc(db('off2'), 'studentProfiles/off2'), { name: 'Officer Two', email: 'off2@nexora.test',
      academicVerified: false, skills: [], preferredRoles: [], certifications: [], updatedAt: serverTimestamp() }));
  });
  it('cannot create a user document for someone else', async () => {
    await assertFails(setDoc(doc(db('newbie'), 'users/other'), { ...newUser('STUDENT'), uid: 'other' }));
  });
  it('cannot register with a different email or extra fields', async () => {
    await assertFails(setDoc(doc(db('newbie'), 'users/newbie'), newUser('STUDENT', { email: 'x@evil.test' })));
    await assertFails(setDoc(doc(db('newbie'), 'users/newbie'), newUser('STUDENT', { isAdmin: true })));
  });
  it('student cannot change role on update', async () => {
    await assertFails(updateDoc(doc(db('kiran'), 'users/kiran'), { role: 'OFFICER' }));
  });
  it('student can change own name and fcm token only', async () => {
    await assertSucceeds(updateDoc(doc(db('kiran'), 'users/kiran'), { name: 'Kiran D', fcmToken: 'tok' }));
    await assertFails(updateDoc(doc(db('kiran'), 'users/kiran'), { email: 'k2@nexora.test' }));
  });
  it('student cannot read another user; officer can', async () => {
    await assertFails(getDoc(doc(db('kiran'), 'users/aisha')));
    await assertSucceeds(getDoc(doc(officer(), 'users/aisha')));
  });
  it('unauthenticated users read nothing', async () => {
    await assertFails(getDoc(doc(anon(), 'placementDrives/d1')));
    await assertFails(getDoc(doc(anon(), 'users/aisha')));
  });
});

// ------------------------------------------------------------------ profiles & verification
describe('student profiles and verification', () => {
  it('student can edit academic fields before verification', async () => {
    await assertSucceeds(updateDoc(doc(db('kiran'), 'studentProfiles/kiran'), { cgpa: 8.25, updatedAt: serverTimestamp() }));
  });
  it('student cannot edit cgpa after verification', async () => {
    await assertFails(updateDoc(doc(db('aisha'), 'studentProfiles/aisha'), { cgpa: 9.9, updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db('aisha'), 'studentProfiles/aisha'), { branch: 'CSE', updatedAt: serverTimestamp() }));
  });
  it('verified student can still edit skills and links', async () => {
    await assertSucceeds(updateDoc(doc(db('aisha'), 'studentProfiles/aisha'),
      { skills: ['java', 'docker'], resumeUrl: 'https://drive.google.com/x', updatedAt: serverTimestamp() }));
  });
  it('student cannot set academicVerified or verifiedBy', async () => {
    await assertFails(updateDoc(doc(db('kiran'), 'studentProfiles/kiran'), { academicVerified: true, updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db('kiran'), 'studentProfiles/kiran'), { verifiedBy: 'kiran', updatedAt: serverTimestamp() }));
  });
  it('student cannot create a pre-verified profile or invalid values', async () => {
    await env.withSecurityRulesDisabled((ctx) => setDoc(doc(ctx.firestore(), 'users/newbie'), { uid: 'newbie', name: 'N', email: 'newbie@nexora.test', role: 'STUDENT' }));
    const base = { name: 'New', email: 'newbie@nexora.test', skills: [], preferredRoles: [], certifications: [], updatedAt: serverTimestamp() };
    await assertFails(setDoc(doc(db('newbie'), 'studentProfiles/newbie'), { ...base, academicVerified: true }));
    await assertFails(setDoc(doc(db('newbie'), 'studentProfiles/newbie'), { ...base, academicVerified: false, cgpa: 11 }));
    await assertSucceeds(setDoc(doc(db('newbie'), 'studentProfiles/newbie'), { ...base, academicVerified: false }));
  });
  it('student cannot read another student profile', async () => {
    await assertFails(getDoc(doc(db('kiran'), 'studentProfiles/aisha')));
    await assertSucceeds(getDoc(doc(db('kiran'), 'studentProfiles/kiran')));
  });
  it('officer verifies a complete record, stamped with self and server time', async () => {
    await assertSucceeds(updateDoc(doc(officer(), 'studentProfiles/kiran'),
      { academicVerified: true, verifiedBy: 'officer', verifiedAt: serverTimestamp(), updatedAt: serverTimestamp() }));
  });
  it('officer cannot forge verifiedBy or edit academic values', async () => {
    await assertFails(updateDoc(doc(officer(), 'studentProfiles/kiran'),
      { academicVerified: true, verifiedBy: 'someoneElse', verifiedAt: serverTimestamp(), updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(officer(), 'studentProfiles/kiran'), { cgpa: 9.5, updatedAt: serverTimestamp() }));
  });
  it('officer cannot verify an incomplete record', async () => {
    await env.withSecurityRulesDisabled((ctx) => updateDoc(doc(ctx.firestore(), 'studentProfiles/kiran'), { cgpa: null }));
    await assertFails(updateDoc(doc(officer(), 'studentProfiles/kiran'),
      { academicVerified: true, verifiedBy: 'officer', verifiedAt: serverTimestamp(), updatedAt: serverTimestamp() }));
  });
  it('officer can un-verify so the student can correct, then the student edits', async () => {
    await assertSucceeds(updateDoc(doc(officer(), 'studentProfiles/aisha'),
      { academicVerified: false, verifiedBy: null, verifiedAt: null, updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(db('aisha'), 'studentProfiles/aisha'), { cgpa: 8.45, updatedAt: serverTimestamp() }));
  });
});

// ------------------------------------------------------------------ drives & companies
describe('drives and companies', () => {
  it('student cannot read a DRAFT drive but can read OPEN and CLOSED', async () => {
    await assertFails(getDoc(doc(db('aisha'), 'placementDrives/dDraft')));
    await assertSucceeds(getDoc(doc(db('aisha'), 'placementDrives/d1')));
    await assertSucceeds(getDoc(doc(db('aisha'), 'placementDrives/dClosed')));
  });
  it('student drive query must exclude drafts', async () => {
    await assertSucceeds(getDocs(query(collection(db('aisha'), 'placementDrives'), where('status', 'in', ['OPEN', 'CLOSED']))));
    await assertFails(getDocs(collection(db('aisha'), 'placementDrives')));
  });
  it('student cannot write drives or companies', async () => {
    await assertFails(setDoc(doc(db('aisha'), 'placementDrives/x'), driveDoc()));
    await assertFails(updateDoc(doc(db('aisha'), 'placementDrives/d1'), { status: 'CLOSED' }));
    await assertFails(setDoc(doc(db('aisha'), 'companies/x'), { name: 'Evil' }));
  });
  it('officer creates, publishes and closes drives; reads drafts', async () => {
    await assertSucceeds(setDoc(doc(officer(), 'placementDrives/new'), driveDoc({ status: 'DRAFT', deadline: null })));
    await assertSucceeds(updateDoc(doc(officer(), 'placementDrives/new'), { status: 'OPEN', deadline: future() }));
    await assertSucceeds(updateDoc(doc(officer(), 'placementDrives/new'), { status: 'CLOSED' }));
    await assertSucceeds(getDoc(doc(officer(), 'placementDrives/dDraft')));
    await assertSucceeds(setDoc(doc(officer(), 'companies/acme'), { name: 'Acme', sector: 'IT', createdBy: 'officer' }));
  });
  it('officer cannot publish an invalid drive', async () => {
    await assertFails(setDoc(doc(officer(), 'placementDrives/bad'), driveDoc({ deadline: null })));
    await assertFails(setDoc(doc(officer(), 'placementDrives/bad'), driveDoc({ status: 'LIVE' })));
  });
});

// ------------------------------------------------------------------ applications
describe('applications', () => {
  const apply = (uid, driveId = 'd1', overrides = {}) =>
    setDoc(doc(db(uid), `applications/${driveId}_${uid}`), appDoc(uid, driveId, overrides));

  it('eligible verified student succeeds (CGPA exactly at minimum)', async () => {
    await assertSucceeds(apply('exact'));
  });
  it('duplicate application is rejected by the server', async () => {
    await assertFails(apply('aisha'));
  });
  it('wrong document id fails', async () => {
    await assertFails(setDoc(doc(db('exact'), 'applications/d1_someoneElse'), appDoc('exact')));
    await assertFails(setDoc(doc(db('exact'), 'applications/random'), appDoc('exact')));
  });
  it('CGPA 6.99 fails', async () => { await assertFails(apply('rahul')); });
  it('ineligible branch fails', async () => { await assertFails(apply('meera')); });
  it('backlogs: max+1 fails, equal to max passes', async () => {
    await assertFails(apply('backlog', 'd1'));
    await assertSucceeds(apply('backlog', 'dBacklog'));
  });
  it('after deadline fails', async () => { await assertFails(apply('exact', 'dExpired')); });
  it('CLOSED drive fails', async () => { await assertFails(apply('exact', 'dClosed')); });
  it('DRAFT drive fails', async () => { await assertFails(apply('exact', 'dDraft')); });
  it('unverified student fails', async () => { await assertFails(apply('kiran')); });
  it('status other than SUBMITTED fails', async () => {
    await assertFails(apply('exact', 'd1', { status: 'SELECTED' }));
    await assertFails(apply('exact', 'd1', { currentStage: 2 }));
  });
  it('forged snapshot, names or history fail', async () => {
    await assertFails(apply('exact', 'd1', { profileSnapshot: { cgpa: 9.9, branch: 'CSE', activeBacklogs: 0, graduationYear: 2027 } }));
    await assertFails(apply('exact', 'd1', { studentName: 'Someone Else' }));
    await assertFails(apply('exact', 'd1', { driveTitle: 'CEO' }));
    await assertFails(apply('exact', 'd1', { stageHistory: [{ stageIndex: 0, result: 'PASSED' }] }));
    await assertFails(apply('exact', 'd1', { matchScore: 101 }));
    await assertFails(apply('exact', 'd1', { appliedAt: Timestamp.fromMillis(0) }));
  });
  it('officer cannot apply on behalf of a student', async () => {
    await assertFails(setDoc(doc(officer(), 'applications/d1_exact'), appDoc('exact')));
  });
  it('student cannot update or delete any application', async () => {
    await assertFails(updateDoc(doc(db('aisha'), 'applications/d1_aisha'), { status: 'SELECTED', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db('aisha'), 'applications/d1_aisha'), { matchScore: 100 }));
    await assertFails(deleteDoc(doc(db('aisha'), 'applications/d1_aisha')));
  });
  it('student reads only own applications', async () => {
    await assertSucceeds(getDoc(doc(db('aisha'), 'applications/d1_aisha')));
    await assertFails(getDoc(doc(db('kiran'), 'applications/d1_aisha')));
    await assertSucceeds(getDocs(query(collection(db('kiran'), 'applications'), where('studentId', '==', 'kiran'))));
    await assertFails(getDocs(collection(db('kiran'), 'applications')));
  });
  it('officer advances one stage with a history entry and notification (batch)', async () => {
    const f = officer();
    const b = writeBatch(f);
    b.update(doc(f, 'applications/d1_aisha'), {
      status: 'IN_PROGRESS', currentStage: 1, updatedAt: serverTimestamp(),
      stageHistory: [{ stageIndex: 0, result: 'PASSED', note: 'Shortlisted', updatedAt: Timestamp.now() }],
    });
    b.set(doc(f, 'notifications/n1'), { title: 'Acme', body: 'Moved', audience: 'STUDENT', targetUid: 'aisha',
      driveId: 'd1', applicationId: 'd1_aisha', createdBy: 'officer', createdAt: serverTimestamp() });
    await assertSucceeds(b.commit());
  });
  it('officer can update only outcome fields, one stage at a time', async () => {
    await assertFails(updateDoc(doc(officer(), 'applications/d1_aisha'), { matchScore: 99 }));
    await assertFails(updateDoc(doc(officer(), 'applications/d1_aisha'), {
      status: 'IN_PROGRESS', currentStage: 2, updatedAt: serverTimestamp(),
      stageHistory: [{ stageIndex: 0, result: 'PASSED' }] }));
    await assertFails(updateDoc(doc(officer(), 'applications/d1_aisha'), {
      status: 'IN_PROGRESS', currentStage: 1, updatedAt: serverTimestamp(), stageHistory: [] }));
  });
  it('final outcomes are immutable', async () => {
    await env.withSecurityRulesDisabled((ctx) => updateDoc(doc(ctx.firestore(), 'applications/d1_aisha'),
      { status: 'REJECTED', stageHistory: [{ stageIndex: 0, result: 'FAILED' }] }));
    await assertFails(updateDoc(doc(officer(), 'applications/d1_aisha'), {
      status: 'IN_PROGRESS', currentStage: 1, updatedAt: serverTimestamp(),
      stageHistory: [{ stageIndex: 0, result: 'FAILED' }, { stageIndex: 0, result: 'PASSED' }] }));
  });
});

// ------------------------------------------------------------------ readiness
describe('readiness activities', () => {
  const act = (o = {}) => ({ type: 'CODING', title: 'Practice', scorePercent: 80, rating: null, durationMin: 45,
    date: Timestamp.now(), createdAt: Timestamp.now(), ...o });
  it('student writes own activities idempotently', async () => {
    await assertSucceeds(setDoc(doc(db('kiran'), 'readinessProgress/kiran/activities/u1'), act()));
    await assertSucceeds(setDoc(doc(db('kiran'), 'readinessProgress/kiran/activities/u1'), act()));
  });
  it('invalid activities are rejected', async () => {
    await assertFails(setDoc(doc(db('kiran'), 'readinessProgress/kiran/activities/u2'), act({ scorePercent: 150 })));
    await assertFails(setDoc(doc(db('kiran'), 'readinessProgress/kiran/activities/u2'), act({ type: 'SLEEP' })));
  });
  it('student cannot read or write another student activities; officer can read', async () => {
    await assertFails(getDoc(doc(db('kiran'), 'readinessProgress/aisha/activities/a1')));
    await assertFails(setDoc(doc(db('kiran'), 'readinessProgress/aisha/activities/x'), act()));
    await assertSucceeds(getDocs(collection(officer(), 'readinessProgress/aisha/activities')));
    await assertFails(setDoc(doc(officer(), 'readinessProgress/aisha/activities/x'), act()));
  });
});

// ------------------------------------------------------------------ notifications
describe('notifications', () => {
  it('student reads ALL and own targeted notifications only', async () => {
    await assertSucceeds(getDocs(query(collection(db('kiran'), 'notifications'), where('audience', '==', 'ALL'))));
    await assertSucceeds(getDocs(query(collection(db('kiran'), 'notifications'), where('targetUid', '==', 'kiran'))));
    await assertSucceeds(getDoc(doc(db('kiran'), 'notifications/forKiran')));
    await assertFails(getDoc(doc(db('aisha'), 'notifications/forKiran')));
    await assertFails(getDocs(collection(db('aisha'), 'notifications')));
  });
  it('students cannot create notifications', async () => {
    await assertFails(setDoc(doc(db('aisha'), 'notifications/x'),
      { title: 'Hi', body: 'Fake', audience: 'ALL', createdBy: 'aisha', createdAt: serverTimestamp() }));
  });
  it('officer publishes announcements stamped with self', async () => {
    await assertSucceeds(setDoc(doc(officer(), 'notifications/x'),
      { title: 'Drive', body: 'New drive', audience: 'ALL', targetUid: null, createdBy: 'officer', createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(officer(), 'notifications/y'),
      { title: 'Drive', body: 'New drive', audience: 'ALL', createdBy: 'someoneElse', createdAt: serverTimestamp() }));
  });
});
