package info.nightscout.androidaps.plugins.pump.insight.descriptors;

public class BasalProfileBlock {

    private int durationMinutes;
    private double basalAmount;

    public int getDuration() {
        return this.durationMinutes;
    }

    public double getBasalAmount() {
        return this.basalAmount;
    }

    public void setDuration(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public void setBasalAmount(double basalAmount) {
        this.basalAmount = basalAmount;
    }
}
