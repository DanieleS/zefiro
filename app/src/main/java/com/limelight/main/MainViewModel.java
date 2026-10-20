package com.limelight.main;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.limelight.computers.HostRepository;

/**
 * The main screen's state that outlives its views: the host repository, which section is on
 * screen, and whether the Library lists hidden games.
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
    private final MutableLiveData<Boolean> showHiddenGames = new MutableLiveData<>(false);

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

    public LiveData<Boolean> showHiddenGames() {
        return showHiddenGames;
    }

    public void setShowHiddenGames(boolean show) {
        if (!Boolean.valueOf(show).equals(showHiddenGames.getValue())) {
            showHiddenGames.setValue(show);
        }
    }

    @Override
    protected void onCleared() {
        repository.close();
    }
}
