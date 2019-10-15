package SaxMic;


import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import javax.sound.sampled.*;


/**
 * @author Cam
 */
public class SaxMic {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args){
            // Load configurations
            
            // Hard coded config as big fallback
            long sampleRate = 48000;
            int sampleSize = 16, channels = 1, bufferSize = 2048, historySize = 10;
            float voicevolume = 1.0f, saxvolume = 0.75f, peakDb = -25f;
            boolean signed = true, bigEndian = true, generateReadme = true, reportDb = false;
            String microphoneName = "microphone";
            
            try{
                System.out.println("Loading config file...");
                Config config = ConfigFactory.load("config");
                sampleRate = config.getLong("SampleRate");
                sampleSize = config.getInt("SampleSizeBits");
                channels = config.getInt("Channels");
                signed = config.getBoolean("Signed");
                bigEndian = config.getBoolean("BigEndian");
                microphoneName = config.getString("MicrophoneName").toLowerCase();
                bufferSize = config.getInt("AudioBufferSize");
                voicevolume = (float)config.getDouble("NormalVolume");
                saxvolume = (float)config.getDouble("QuietVolume");
                peakDb = (float)config.getDouble("PeakDb");
                historySize = config.getInt("PeakHistorySize");
                generateReadme = config.getBoolean("GenerateReadme");
                reportDb = config.getBoolean("ReportDb");
            }catch(Exception ex){
                System.err.println("Failed to load any config files!! Falling back to hard-coded defaults.");
                ex.printStackTrace();
            }
            
            System.out.println("");
            System.out.println("Loaded Configuration: ");
            System.out.println(" - SampleRate: " + sampleRate);
            System.out.println(" - SampleSizeBits: " + sampleSize);
            System.out.println(" - Channels: " + channels);
            System.out.println(" - Signed: " + signed);
            System.out.println(" - BigEndian: " + bigEndian);
            System.out.println(" - MicrophoneName: " + microphoneName);
            System.out.println(" - AudioBufferSize: " + bufferSize);
            System.out.println(" - NormalVolume: " + voicevolume);
            System.out.println(" - QuietVolume: " + saxvolume);
            System.out.println(" - PeakDb: " + peakDb);
            System.out.println(" - PeakHistorySize: " + historySize);
            System.out.println(" - generateReadme: " + generateReadme);
            System.out.println(" - reportDb: " + reportDb);
            System.out.println("");
            
            // Generate readme
            if(generateReadme){
                System.out.println("Regenerating readme.txt...");
                try{
                    File readmeFile = new File(new File(SaxMic.class.getProtectionDomain().getCodeSource().getLocation().getFile()).getParent() + File.separator + "readme.txt");
                    PrintWriter pw = new PrintWriter(readmeFile);
                    pw.println("This program can be used to automatically decrease the volume of your microphone if it hits a certain volume.");
                    pw.println("Created by CJKellner (cjkellner.net)");
                    pw.println("");
                    pw.println("Explanation of config file: (Default values should usually work. Delete config to reset to default.");
                    pw.println("SampleRate - Defines the sample rate of the mic");
                    pw.println("SampleSizeBits - Defines the number of bits per sample");
                    pw.println("Channels - Defines the number of channels");
                    pw.println("Signed - Defines whether the input is signed or not");
                    pw.println("BigEndian - Defines whether the data is to be stored as big endian");
                    pw.println("MicrophoneName - Name (or part of the name) of the microphone to adjust. List of available options is below");
                    pw.println("AudioBufferSize - Buffer used for audio data. Lower numbers for less latency, higher numbers for less processing power");
                    pw.println("NormalVolume - Volume of the microphone when not above the peak");
                    pw.println("QuietVolume - Temporary volume of the microphone when the peak has beed exceeded");
                    pw.println("PeakDb - The peak at which to quiet the microphone (Note: doesn't always line up with actual Db)");
                    pw.println("PeakHistorySize - How long to keep a history of the peaks. Higher sizes take longer to go back to normal volume");
                    pw.println("GenerateReadme - Regenerates this file every time");
                    pw.println("ReportDb - Outputs recorded decibels to the console");
                    pw.println("");
                    pw.println("List of available microphones:");
                    for(Mixer.Info in:AudioSystem.getMixerInfo()){
                        pw.println(" - " + in.getName());
                    }
                    pw.flush();
                    pw.close();
                }catch(FileNotFoundException ex){
                    System.err.println("Error regenerating readme.txt!");
                    ex.printStackTrace();
                }
            }
            
            // Set up input and output audio devices
            AudioFormat format = new AudioFormat(sampleRate, sampleSize, channels, signed, bigEndian);
            DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, format);
            DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, format);
            
            try{
                TargetDataLine targetLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
                targetLine.open(format);
                targetLine.start();

                SourceDataLine sourceLine = (SourceDataLine) AudioSystem.getLine(sourceInfo);
                sourceLine.open(format);
                sourceLine.start();

                // Find microphone specified in config
                Line microphone = AudioSystem.getLine(targetInfo);
                for(Mixer.Info in:AudioSystem.getMixerInfo()){
                    if(in.getName().toLowerCase().contains(microphoneName)){
                        try{
                            Mixer m = AudioSystem.getMixer(in);
                            microphone = m.getLine(m.getSourceLineInfo()[0]);
                            System.out.println("Using Microphone: " + in.getName());
                        }catch(ArrayIndexOutOfBoundsException ex){}
                    }
                }
                microphone.open();

                // Define buffers
                int numBytesRead;
                byte[] targetData = new byte[bufferSize];
                float[] samples = new float[bufferSize/2];
                ArrayList<Float> history = new ArrayList<Float>();
                boolean saxophone = false;

                while (true) {
                    // Read audio input and calculate the amplitude
                    numBytesRead = targetLine.read(targetData, 0, targetData.length);
                    for(int i=0, s=0; i<numBytesRead;){
                        int sample = 0;
                        if(bigEndian){
                            sample |= targetData[i++] << 8;
                            sample |= targetData[i++] & 0xFF;
                        }else{
                            sample |= targetData[i++] & 0xFF;
                            sample |= targetData[i++] << 8;
                        }
                        samples[s++] = sample/32768f;
                    }
                    float amplitude = 0f;
                    for(float sample:samples){
                        amplitude += sample*sample;
                    }
                    amplitude = (float)Math.sqrt(amplitude / samples.length)/1.4f;

                    //convert logarithmic amplitude to linear decibels
                    amplitude = 20*(float)Math.log10(amplitude);
                    if(saxophone) amplitude /= voicevolume/saxvolume;
                    float amp_adj = amplitude;

                    //create a history to determine recent peak
                    history.add(amp_adj);
                    if(history.size() > historySize) history.remove(0);
                    float peak = -100f;
                    for(float amp:history){
                        if(amp>peak){
                            peak=amp;
                        }
                    }
                    
                    if(reportDb) System.out.println("Db: " + String.valueOf(amp_adj).substring(0,Math.min(6, String.valueOf(amp_adj).length()-1)) + "   Historical Peak Db: " + String.valueOf(peak).substring(0,Math.min(6, String.valueOf(peak).length()-1)));
                    
                    //determine whether or not to quiet microphone
                    if(peak>peakDb && !saxophone){
                        ((FloatControl)((CompoundControl)microphone.getControls()[0]).getMemberControls()[1]).setValue(saxvolume);
                        saxophone = true;
                    }
                    if(peak<peakDb && saxophone){
                        ((FloatControl)((CompoundControl)microphone.getControls()[0]).getMemberControls()[1]).setValue(voicevolume);
                        saxophone = false;
                    }
                }
            }catch(LineUnavailableException ex){
                System.err.println("FATAL ERROR: Can't open audio lines!");
                ex.printStackTrace();
            }
    }
}
