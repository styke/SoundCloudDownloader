/*
 * SoundCloudDownloader
 * Copyright (C) 2015 Ruben Anders
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package cf.randers.scd;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.StandardArtwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CommandLineInterface
{

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandLineInterface.class);

    public static void main(String[] args)
    {
        CommandLineInterface commandLineInterface = new CommandLineInterface();
        JCommander jCommander = new JCommander(commandLineInterface, args);
        jCommander.setProgramName("SoundCloudDownloader");
        if (commandLineInterface.help)
            jCommander.usage();
        commandLineInterface.run();
    }

    private void run()
    {
        if (params == null)
            return;
        LOGGER.info("Making temp dir...");
        File tmpDir = new File("tmp/");
        //noinspection ResultOfMethodCallIgnored
        tmpDir.mkdirs();
        tmpDir.deleteOnExit();
        BlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(params.size());
        maximumConcurrentConnections = Math.min(params.size(), maximumConcurrentConnections > params.size() ? params.size() : maximumConcurrentConnections);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(maximumConcurrentConnections, maximumConcurrentConnections, 0, TimeUnit.NANOSECONDS, tasks);
        LOGGER.info("Starting to execute " + params.size() + " thread(s)...");
        for (String param : params)
        {
            executor.execute(() -> {
                LOGGER.info("Started thread for " + param);
                Map json;
                byte[] artworkBytes = new byte[0];

                LOGGER.info("Resolving and querying track info...");
                try (CloseableHttpClient client = HttpClients.createDefault();
                     CloseableHttpResponse response = client.execute(
                             new HttpGet(new URIBuilder()
                                                 .setScheme("https")
                                                 .setHost("api.soundcloud.com")
                                                 .setPath("/resolve")
                                                 .addParameter("url", param)
                                                 .addParameter("client_id", clientID)
                                                 .build()));
                     InputStreamReader inputStreamReader = new InputStreamReader(response.getEntity().getContent()))
                {
                    json = new Gson().fromJson(inputStreamReader, Map.class);
                    EntityUtils.consumeQuietly(response.getEntity());
                } catch (Exception e)
                {
                    e.printStackTrace();
                    return;
                }

                LOGGER.info("Downloading mp3 to file...");
                File tmpFile = new File("tmp/" + String.format("%.0f", ((Double) json.get("id")).doubleValue()) + ".mp3");

                try (CloseableHttpClient client = HttpClients.createDefault();
                     CloseableHttpResponse response = client.execute(new HttpGet(json.get("stream_url") + "?client_id=" + clientID)))
                {
                    IOUtils.copy(response.getEntity().getContent(), new FileOutputStream(tmpFile));
                    EntityUtils.consumeQuietly(response.getEntity());
                } catch (Exception e)
                {
                    e.printStackTrace();
                    return;
                }

                boolean hasArtwork = json.get("artwork_url") != null;

                if (hasArtwork)
                {
                    LOGGER.info("Downloading artwork jpg into memory...");
                    try (CloseableHttpClient client = HttpClients.createDefault();
                         CloseableHttpResponse response = client.execute(
                                 new HttpGet(((String) json.get("artwork_url")).replace("-large.jpg", "-t500x500.jpg") + "?client_id=" + clientID)))
                    {
                        artworkBytes = IOUtils.toByteArray(response.getEntity().getContent());
                        EntityUtils.consumeQuietly(response.getEntity());
                    } catch (Exception e)
                    {
                        e.printStackTrace();
                        return;
                    }
                }

                try
                {
                    LOGGER.info("Reading temp file into AudioFile object...");
                    // Read audio file from tmp directory
                    AudioFile audioFile = AudioFileIO.read(tmpFile);

                    // Set Artwork
                    Tag tag = audioFile.getTagAndConvertOrCreateAndSetDefault();
                    if (hasArtwork)
                    {
                        StandardArtwork artwork = new StandardArtwork();
                        artwork.setBinaryData(artworkBytes);
                        artwork.setImageFromData();
                        tag.addField(artwork);
                    }
                    tag.addField(FieldKey.TITLE, json.get("title").toString());
                    tag.addField(FieldKey.ARTIST, ((Map) json.get("user")).get("username").toString());

                    LOGGER.info("Saving audio file...");
                    new AudioFileIO().writeFile(audioFile, json.get("permalink").toString());
                } catch (Exception e)
                {
                    e.printStackTrace();
                }

                LOGGER.info("Deleting temp file...");
                //noinspection ResultOfMethodCallIgnored
                try
                {
                    Files.delete(tmpFile.toPath());
                } catch (IOException e)
                {
                    e.printStackTrace();
                    return;
                }
                LOGGER.info("Done.");
            });
        }
        executor.shutdown();
    }

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    @Parameter(description = "List of SoundCloud links to process")
    private List<String> params;

    @Parameter(names = {"--apitoken", "-A"}, description = "API token to use")
    private String clientID = "6f141f64ad25764c3345ec3f92c21770";

    @Parameter(names = {"--connections", "-C"}, description = "Maximum amount of songs to be processed concurrently.")
    private int maximumConcurrentConnections = 4;

    @Parameter(names = {"--help", "-H", "-?"}, description = "Display help.")
    private boolean help = false;
}
