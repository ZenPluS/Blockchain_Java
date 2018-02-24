package it.unica.enrico.interfaces;

import it.unica.enrico.models.Data;
import java.util.Queue;

public interface Sender {

    public Queue<Data> getQueue();

    public boolean isReady();
}