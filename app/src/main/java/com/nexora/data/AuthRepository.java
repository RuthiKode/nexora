package com.nexora.data;

import androidx.lifecycle.LiveData;

import com.google.android.gms.tasks.Tasks;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.nexora.domain.Models;
import com.nexora.domain.Models.StudentProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Authentication, the users collection (roles) and student profiles, including officer verification. */
public final class AuthRepository {
    private final FirebaseAuth auth;
    private final FirebaseFirestore db;

    public AuthRepository(FirebaseAuth auth, FirebaseFirestore db) {
        this.auth = auth;
        this.db = db;
    }

    public String uid() {
        FirebaseUser u = auth.getCurrentUser();
        return u == null ? null : u.getUid();
    }

    public String email() {
        FirebaseUser u = auth.getCurrentUser();
        return u == null ? null : u.getEmail();
    }

    public void signIn(String email, String password, Resource.Callback<String> cb) {
        auth.signInWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener(r -> loadRole(cb))
                .addOnFailureListener(e -> cb.onResult(Resource.error(authMessage(e), null)));
    }

    /** Registers a STUDENT (users + studentProfiles) or a placement OFFICER (users document only). */
    public void register(String name, String email, String password, boolean officer, Resource.Callback<String> cb) {
        auth.createUserWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener(r -> {
                    if (officer) createOfficerDoc(name.trim(), email(), cb);
                    else createStudentDocs(name.trim(), email(), cb);
                })
                .addOnFailureListener(e -> cb.onResult(Resource.error(authMessage(e), null)));
    }

    private void createOfficerDoc(String name, String email, Resource.Callback<String> cb) {
        Map<String, Object> user = new HashMap<>();
        user.put("uid", uid());
        user.put("name", name);
        user.put("email", email);
        user.put("role", Models.ROLE_OFFICER);
        user.put("createdAt", FieldValue.serverTimestamp());
        db.collection(FirestoreMapper.USERS).document(uid()).set(user)
                .addOnSuccessListener(v -> cb.onResult(Resource.success(Models.ROLE_OFFICER)))
                .addOnFailureListener(e -> cb.onResult(Resource.error(FirestoreMapper.errorMessage(e), null)));
    }

    /** Reads users/{uid}.role. If the user document is missing (interrupted registration) it is completed as STUDENT. */
    public void loadRole(Resource.Callback<String> cb) {
        String uid = uid();
        if (uid == null) { cb.onResult(Resource.error("Not signed in", null)); return; }
        db.collection(FirestoreMapper.USERS).document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists() && doc.getString("role") != null) {
                        cb.onResult(Resource.success(doc.getString("role")));
                    } else {
                        String email = email() == null ? "" : email();
                        createStudentDocs(email.contains("@") ? email.substring(0, email.indexOf('@')) : "Student", email, cb);
                    }
                })
                .addOnFailureListener(e -> cb.onResult(Resource.error(FirestoreMapper.errorMessage(e), null)));
    }

    /** Sequential writes: the profile rule checks the STUDENT role in users/{uid}, so that must exist first. */
    private void createStudentDocs(String name, String email, Resource.Callback<String> cb) {
        String uid = uid();
        // Rules require users/{uid}.email to equal the auth token email, so always use Auth's copy.
        Map<String, Object> user = new HashMap<>();
        user.put("uid", uid);
        user.put("name", name);
        user.put("email", email);
        user.put("role", Models.ROLE_STUDENT);
        user.put("createdAt", FieldValue.serverTimestamp());
        DocumentReference profileRef = db.collection(FirestoreMapper.PROFILES).document(uid);
        db.collection(FirestoreMapper.USERS).document(uid).set(user)
                .continueWithTask(t -> {
                    if (!t.isSuccessful()) throw t.getException();
                    return profileRef.get();
                })
                .continueWithTask(t -> {
                    if (!t.isSuccessful()) throw t.getException();
                    if (t.getResult().exists()) return Tasks.<Void>forResult(null);
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", name);
                    p.put("email", email);
                    p.put("academicVerified", false);
                    p.put("skills", new ArrayList<String>());
                    p.put("preferredRoles", new ArrayList<String>());
                    p.put("certifications", new ArrayList<String>());
                    p.put("updatedAt", FieldValue.serverTimestamp());
                    return profileRef.set(p);
                })
                .addOnSuccessListener(v -> cb.onResult(Resource.success(Models.ROLE_STUDENT)))
                .addOnFailureListener(e -> cb.onResult(Resource.error(FirestoreMapper.errorMessage(e), null)));
    }

    public void resetPassword(String email, Resource.Callback<Void> cb) {
        auth.sendPasswordResetEmail(email.trim())
                .addOnSuccessListener(v -> cb.onResult(Resource.success(null)))
                .addOnFailureListener(e -> cb.onResult(Resource.error(authMessage(e), null)));
    }

    public void signOut() { auth.signOut(); }

    public void saveFcmToken(String token) {
        String uid = uid();
        if (uid == null || token == null) return;
        db.collection(FirestoreMapper.USERS).document(uid).update("fcmToken", token);
    }

    // ---------------------------------------------------------------- profiles

    public LiveData<Resource<StudentProfile>> profile(String uid) {
        return new FirestoreMapper.DocLiveData<>(db.collection(FirestoreMapper.PROFILES).document(uid), FirestoreMapper::profile);
    }

    public void saveProfile(StudentProfile p, Resource.Callback<Void> cb) {
        Map<String, Object> m = FirestoreMapper.profileMap(p, !p.academicVerified);
        m.put("updatedAt", FieldValue.serverTimestamp());
        db.collection(FirestoreMapper.PROFILES).document(p.uid).update(m)
                .continueWithTask(t -> {
                    if (!t.isSuccessful()) throw t.getException();
                    return db.collection(FirestoreMapper.USERS).document(p.uid).update("name", p.name);
                })
                .addOnSuccessListener(v -> cb.onResult(Resource.success(null)))
                .addOnFailureListener(e -> cb.onResult(Resource.error(FirestoreMapper.errorMessage(e), null)));
    }

    /** Officer only (rules): every student profile, for the verification queue and analytics. */
    public LiveData<Resource<List<StudentProfile>>> allProfiles() {
        return new FirestoreMapper.QueryLiveData<>(
                db.collection(FirestoreMapper.PROFILES).orderBy("updatedAt", Query.Direction.ASCENDING),
                FirestoreMapper::profile);
    }

    /** Officer only: verify locks academic fields; un-verify lets the student correct them. */
    public void setVerified(String studentUid, boolean verified, Resource.Callback<Void> cb) {
        Map<String, Object> m = new HashMap<>();
        m.put("academicVerified", verified);
        m.put("verifiedBy", verified ? uid() : null);
        m.put("verifiedAt", verified ? FieldValue.serverTimestamp() : null);
        m.put("updatedAt", FieldValue.serverTimestamp());
        db.collection(FirestoreMapper.PROFILES).document(studentUid).update(m)
                .addOnSuccessListener(v -> cb.onResult(Resource.success(null)))
                .addOnFailureListener(e -> cb.onResult(Resource.error(FirestoreMapper.errorMessage(e), null)));
    }

    private static String authMessage(Exception e) {
        if (e instanceof FirebaseAuthUserCollisionException) return "An account with this email already exists.";
        if (e instanceof FirebaseAuthInvalidUserException) return "No account found for this email.";
        if (e instanceof FirebaseAuthInvalidCredentialsException) return "Incorrect email or password.";
        String m = e.getMessage();
        if (m != null && m.contains("network")) return "Network error. Check your connection and try again.";
        return m == null ? "Authentication failed." : m;
    }
}
