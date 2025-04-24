package edu.duke.ece568.erss.amazon;

public class Destination {
    private int x;
    private int y;

    public Destination(int x, int y){
        this.x = x;
        this.y = y;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    @Override
    public String toString() {
        return String.format("<%d, %d>", x, y);
    }
}
