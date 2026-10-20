package com.limelight.main;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.limelight.computers.HostRepository;

/**
 * The main screen's state that outlives its views: the host repository, and which section is on
 * screen.
 *
 * <p>It is scoped to the activity, not to the process. With the computer manager taking several
 * listeners, nothing else needs the repository's hosts, and an application-wide copy would only
 * be global state with no owner to stop it.
 *
 * <h3>Lifecycle</h3>
 *
 * <pre>
 *   created      ── the repository is made; it binds the service on its first start
 *   onStart()    ── repo.start(): polling while the activity is visible
 *   onStop()     ── repo.stop():  polling stops, the binding stays
 *   onCleared()  ── repo.close(): the binding goes with the activity, for good
 * </pre>
 *
 * The repository reads its preferences when it is made, so the activity sets the defaults
 * before it first asks for this model. Main thread only.
 */
public class MainViewModel extends AndroidViewModel {

    private final HostRepository repository;
    private Section section = Section.LIBRARY;

    public MainViewModel(@NonNull Application application) {
        super(application);
        repository = new HostRepository(application);
    }

    public HostRepository repository() {
        return repository;
    }

    /** From the activity's onStart. */
    public void onStart() {
        repository.start();
    }

    /** From the activity's onStop. */
    public void onStop() {
        repository.stop();
    }

    public Section section() {
        return section;
    }

    public void setSection(@NonNull Section section) {
        this.section = section;
    }

    @Override
    protected void onCleared() {
        repository.close();
    }
}
