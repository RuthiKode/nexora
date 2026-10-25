// Seeds demo data (NEXORA_PLAN.md §10) with the Admin SDK, which bypasses security rules.
// This is the ONLY way officer accounts are created.
//   Emulator:       npm run seed               (emulators must be running: npm run emulators)
//   Real project:   node seed/seed.js --service-account ./service-account.json
// Never commit the service-account file (it is in .gitignore).
const { initializeApp, cert } = require('firebase-admin/app');
const { getFirestore, Timestamp, FieldValue } = require('firebase-admin/firestore');
const { getAuth } = require('firebase-admin/auth');

const args = process.argv.slice(2);
const emulator = args.includes('--emulator');
const saIndex = args.indexOf('--service-account');

if (emulator) {
  process.env.FIRESTORE_EMULATOR_HOST = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080';
  process.env.FIREBASE_AUTH_EMULATOR_HOST = process.env.FIREBASE_AUTH_EMULATOR_HOST || '127.0.0.1:9099';
  initializeApp({ projectId: 'demo-nexora' });
} else if (saIndex >= 0 && args[saIndex + 1]) {
  initializeApp({ credential: cert(require(require('node:path').resolve(args[saIndex + 1]))) });
} else {
  console.error('Usage: node seed/seed.js --emulator | --service-account <path>');
  process.exit(1);
}

const db = getFirestore();
const auth = getAuth();
const DAY = 24 * 60 * 60 * 1000;
const at = (days) => Timestamp.fromMillis(Date.now() + days * DAY);
const PASSWORD = 'Test@1234';

const STUDENTS = [
  { uid: 'aisha', name: 'Aisha Khan', rollNumber: '21CSAI001', branch: 'CSE_AI', cgpa: 8.4, activeBacklogs: 0, graduationYear: 2027, verified: true,
    skills: ['java', 'python', 'sql', 'git'], preferredRoles: ['SDE', 'ML_AI'], certifications: ['AWS Cloud Practitioner'],
    resumeUrl: 'https://drive.google.com/file/d/aisha-resume/view', summary: 'Final-year CSE (AI) student who enjoys backend systems.',
    githubUrl: 'https://github.com/aisha-khan' },
  { uid: 'rahul', name: 'Rahul Verma', rollNumber: '21CS042', branch: 'CSE', cgpa: 6.99, activeBacklogs: 0, graduationYear: 2027, verified: true,
    skills: ['javascript', 'react', 'sql'], preferredRoles: ['SDE'], certifications: [] },
  { uid: 'meera', name: 'Meera Nair', rollNumber: '21ME015', branch: 'ME', cgpa: 8.9, activeBacklogs: 0, graduationYear: 2027, verified: true,
    skills: ['autocad', 'matlab', 'python'], preferredRoles: ['CORE'], certifications: ['CSWA'] },
  { uid: 'kiran', name: 'Kiran Das', rollNumber: '21CS099', branch: 'CSE', cgpa: 8.1, activeBacklogs: 0, graduationYear: 2027, verified: false,
    skills: ['java', 'sql'], preferredRoles: ['SDE', 'DATA'], certifications: [] },
];

const COMPANIES = [
  { id: 'acme', name: 'Acme Software', sector: 'Product engineering', website: 'https://example.com/acme', description: 'Builds developer tools used by 10k teams.' },
  { id: 'northwind', name: 'Northwind Analytics', sector: 'Data & analytics', website: 'https://example.com/northwind', description: 'Analytics consulting for retail and finance.' },
  { id: 'vertex', name: 'Vertex Motors', sector: 'Automotive', website: 'https://example.com/vertex', description: 'EV drivetrain manufacturer.' },
  { id: 'lumina', name: 'Lumina AI', sector: 'Artificial intelligence', website: 'https://example.com/lumina', description: 'Applied ML for healthcare imaging.' },
];

const stages = (...middle) => [{ name: 'Application Submitted', type: 'SUBMISSION', scheduledAt: null, venueOrLink: null },
  ...middle.map(([name, type, days, venue]) => ({ name, type, scheduledAt: days == null ? null : at(days), venueOrLink: venue || null })),
  { name: 'Final Outcome', type: 'OUTCOME', scheduledAt: null, venueOrLink: null }];

function drive(id, companyId, o) {
  const c = COMPANIES.find((x) => x.id === companyId);
  return [id, {
    companyId, companyName: c.name, applicantCount: 0, createdBy: 'officer', createdAt: at(-20), updatedAt: at(-1),
    location: 'Bengaluru', description: '', requiredSkills: [], prepFocus: [], ...o,
  }];
}

const DRIVES = [
  // The boundary drive: min CGPA 7.00, CSE/CSE_AI, 0 backlogs, batch 2027, closes in 2 days.
  drive('sde-intern', 'acme', { title: 'SDE Intern', roleCategory: 'SDE', jobType: 'INTERN_PPO', packageLpa: 12,
    description: 'Work on the core API platform. 6-month internship with a pre-placement offer.',
    criteria: { minCgpa: 7.0, eligibleBranches: ['CSE', 'CSE_AI'], maxActiveBacklogs: 0, graduationYears: [2027] },
    requiredSkills: ['java', 'python', 'sql', 'docker'], prepFocus: ['CODING', 'INTERVIEW'], deadline: at(2), status: 'OPEN',
    stages: stages(['Online Assessment', 'TEST', 4, 'https://example.com/oa'], ['Technical Interview', 'INTERVIEW', 7, 'Placement Cell, Block A'], ['HR Interview', 'INTERVIEW', 9, 'Placement Cell, Block A']) }),
  drive('data-analyst', 'northwind', { title: 'Data Analyst', roleCategory: 'DATA', jobType: 'FULL_TIME', packageLpa: 8.5, location: 'Hyderabad',
    description: 'Build dashboards and analyses for retail clients.',
    criteria: { minCgpa: 6.5, eligibleBranches: ['CSE', 'CSE_AI', 'IT', 'ECE'], maxActiveBacklogs: 1, graduationYears: [2027] },
    requiredSkills: ['sql', 'python', 'excel', 'tableau'], prepFocus: ['APTITUDE', 'SKILLS'], deadline: at(10), status: 'OPEN',
    stages: stages(['Aptitude Test', 'TEST', 12, 'Online'], ['Case Interview', 'INTERVIEW', 15, 'Online']) }),
  drive('get-core', 'vertex', { title: 'Graduate Engineer Trainee', roleCategory: 'CORE', jobType: 'FULL_TIME', packageLpa: 7, location: 'Pune',
    description: 'Rotational programme across design and manufacturing.',
    criteria: { minCgpa: 7.5, eligibleBranches: ['ME', 'EEE', 'ECE'], maxActiveBacklogs: 0, graduationYears: [2027] },
    requiredSkills: ['autocad', 'matlab'], prepFocus: ['APTITUDE', 'INTERVIEW'], deadline: at(5), status: 'OPEN',
    stages: stages(['Technical Test', 'TEST', 6, 'Seminar Hall 2'], ['Panel Interview', 'INTERVIEW', 8, 'Seminar Hall 2']) }),
  drive('ml-engineer', 'lumina', { title: 'ML Engineer', roleCategory: 'ML_AI', jobType: 'FULL_TIME', packageLpa: 18,
    description: 'Train and ship imaging models.',
    criteria: { minCgpa: 8.0, eligibleBranches: ['CSE', 'CSE_AI'], maxActiveBacklogs: 0, graduationYears: [2027] },
    requiredSkills: ['python', 'machine learning', 'sql'], prepFocus: ['CODING'], deadline: at(6), status: 'OPEN',
    stages: stages(['Coding Round', 'TEST', 7, 'https://example.com/hackerrank'], ['ML Interview', 'INTERVIEW', 10, 'Online']) }),
  drive('cloud-support', 'acme', { title: 'Cloud Support Associate', roleCategory: 'OTHER', jobType: 'FULL_TIME', packageLpa: 6.5,
    description: 'Customer-facing cloud support role.',
    criteria: { minCgpa: 6.0, eligibleBranches: ['CSE', 'CSE_AI', 'IT', 'ECE', 'EEE'], maxActiveBacklogs: 2, graduationYears: [2027] },
    requiredSkills: ['linux', 'networking'], prepFocus: ['INTERVIEW'], deadline: at(-3), status: 'CLOSED',
    stages: stages(['Technical Interview', 'INTERVIEW', null], ['HR Interview', 'INTERVIEW', null]) }),
  drive('product-analyst', 'northwind', { title: 'Product Analyst', roleCategory: 'ANALYST', jobType: 'INTERNSHIP', packageLpa: 5,
    description: 'Draft — not visible to students.',
    criteria: { minCgpa: 7.0, eligibleBranches: ['CSE', 'IT'], maxActiveBacklogs: 0, graduationYears: [2027] },
    requiredSkills: ['sql'], prepFocus: ['APTITUDE'], deadline: at(20), status: 'DRAFT', stages: stages() }),
];

function application(driveId, uid, status, currentStage, history, matchScore, daysAgo) {
  const s = STUDENTS.find((x) => x.uid === uid);
  const d = DRIVES.find((x) => x[0] === driveId)[1];
  return [`${driveId}_${uid}`, {
    driveId, studentId: uid, driveTitle: d.title, companyName: d.companyName, studentName: s.name, studentRoll: s.rollNumber,
    profileSnapshot: { cgpa: s.cgpa, branch: s.branch, activeBacklogs: s.activeBacklogs, graduationYear: s.graduationYear },
    matchScore, status, currentStage, appliedAt: at(-daysAgo), updatedAt: at(-1),
    stageHistory: history.map(([i, r, note]) => ({ stageIndex: i, result: r, note: note || null, updatedAt: at(-daysAgo + i + 1) })),
  }];
}

const APPLICATIONS = [
  application('cloud-support', 'aisha', 'SELECTED', 3, [[0, 'PASSED'], [1, 'PASSED', 'Strong troubleshooting'], [2, 'PASSED']], 64, 12),
  application('cloud-support', 'rahul', 'REJECTED', 1, [[0, 'PASSED'], [1, 'FAILED', 'Needs more Linux depth']], 52, 12),
  application('data-analyst', 'rahul', 'IN_PROGRESS', 1, [[0, 'PASSED', 'Shortlisted']], 58, 4),
  application('data-analyst', 'aisha', 'SUBMITTED', 0, [], 61, 2),
];

async function upsertUser(uid, email, name) {
  try { await auth.deleteUser(uid); } catch (e) { /* not present */ }
  await auth.createUser({ uid, email, password: PASSWORD, displayName: name, emailVerified: true });
}

async function main() {
  // Officer: provisioned here (Admin SDK) — the app never lets anyone register as an officer.
  await upsertUser('officer', 'officer@nexora.test', 'Placement Officer');
  await db.doc('users/officer').set({ uid: 'officer', name: 'Placement Officer', email: 'officer@nexora.test', role: 'OFFICER', createdAt: FieldValue.serverTimestamp() });

  for (const s of STUDENTS) {
    const email = `${s.uid}@nexora.test`;
    await upsertUser(s.uid, email, s.name);
    await db.doc(`users/${s.uid}`).set({ uid: s.uid, name: s.name, email, role: 'STUDENT', createdAt: FieldValue.serverTimestamp() });
    await db.doc(`studentProfiles/${s.uid}`).set({
      name: s.name, email, rollNumber: s.rollNumber, branch: s.branch, cgpa: s.cgpa, activeBacklogs: s.activeBacklogs,
      graduationYear: s.graduationYear, academicVerified: s.verified,
      ...(s.verified ? { verifiedBy: 'officer', verifiedAt: at(-15) } : {}),
      skills: s.skills, preferredRoles: s.preferredRoles, certifications: s.certifications,
      resumeUrl: s.resumeUrl || null, summary: s.summary || null, linkedinUrl: null, githubUrl: s.githubUrl || null,
      updatedAt: at(-15),
    });
  }

  for (const c of COMPANIES) {
    const { id, ...data } = c;
    await db.doc(`companies/${id}`).set({ ...data, logoInitials: data.name.split(' ').map((w) => w[0]).join('').slice(0, 2), createdBy: 'officer', createdAt: at(-30) });
  }
  for (const [id, d] of DRIVES) {
    d.applicantCount = APPLICATIONS.filter(([, a]) => a.driveId === id).length;
    await db.doc(`placementDrives/${id}`).set(d);
  }
  for (const [id, a] of APPLICATIONS) await db.doc(`applications/${id}`).set(a);

  // Aisha: six weeks of practice; aptitude strong, coding weak (drives the readiness suggestion).
  const acts = [];
  const add = (daysAgo, type, title, o) => acts.push({ id: `seed-aisha-${acts.length}`, type, title, scorePercent: null, rating: null,
    durationMin: 45, date: at(-daysAgo), createdAt: at(-daysAgo), ...o });
  [2, 6, 11, 18, 26, 33].forEach((d, i) => add(d, 'APTITUDE', `Aptitude mock #${i + 1}`, { scorePercent: 78 + i }));
  [5, 21, 38].forEach((d, i) => add(d, 'CODING', `Coding practice set #${i + 1}`, { scorePercent: 40 + i * 5, durationMin: 90 }));
  [9].forEach((d) => add(d, 'INTERVIEW', 'Mock technical interview', { rating: 3, durationMin: 40 }));
  [3, 10, 16, 24, 40].forEach((d, i) => add(d, 'SKILLS', `Docker course module ${i + 1}`, { durationMin: 60 }));
  for (const a of acts) {
    const { id, ...data } = a;
    await db.doc(`readinessProgress/aisha/activities/${id}`).set(data);
  }

  const notices = [
    ['Placement season 2026–27 is live', 'Complete your profile and get your academic record verified to start applying.', -10],
    ['Acme SDE Intern closes soon', 'Applications for the Acme Software SDE Intern drive close in 2 days.', -1],
    ['Resume clinic on Friday', 'Bring your resume link to the placement cell, 3–5 pm, for a quick review.', -3],
  ];
  for (const [i, [title, body, d]] of notices.entries()) {
    await db.doc(`notifications/seed-${i}`).set({ title, body, audience: 'ALL', targetUid: null, driveId: null, applicationId: null, createdBy: 'officer', createdAt: at(d) });
  }
  console.log(`Seeded: 1 officer, ${STUDENTS.length} students (password ${PASSWORD}), ${COMPANIES.length} companies, ` +
    `${DRIVES.length} drives, ${APPLICATIONS.length} applications, ${acts.length} activities, ${notices.length} announcements.`);
}

main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
