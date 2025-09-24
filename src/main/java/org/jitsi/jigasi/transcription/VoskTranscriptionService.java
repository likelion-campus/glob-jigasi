/*
 * Jigasi, the JItsi GAteway to SIP.
 *
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jigasi.transcription;

import org.eclipse.jetty.websocket.api.*;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.client.*;
import org.json.simple.*;
import org.json.simple.parser.*;
import org.jitsi.jigasi.*;
import org.jitsi.utils.logging.*;

import javax.media.format.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;


/**
 * Implements a TranscriptionService which uses local
 * Vosk websocket transcription service.
 * <p>
 * See https://github.com/alphacep/vosk-server for
 * information about server
 *
 * @author Nik Vaessen
 * @author Damian Minkov
 * @author Nickolay V. Shmyrev
 */
public class VoskTranscriptionService
        extends AbstractTranscriptionService
{

    /**
     * The logger for this class
     */
    private final static Logger logger
            = Logger.getLogger(VoskTranscriptionService.class);

    /**
     * The config key of the websocket to the speech-to-text service.
     */
    public final static String WEBSOCKET_URL
            = "org.jitsi.jigasi.transcription.vosk.websocket_url";

    public final static String DEFAULT_WEBSOCKET_URL = "ws://localhost:2700";

    private final static String EOF_MESSAGE = "{\"eof\" : 1}";

    /**
     * The config value of the websocket to the speech-to-text service.
     */
    private String websocketUrlConfig;

    /**
     * The URL of the websocket to the speech-to-text service.
     */
    private String websocketUrl;

    private final JSONParser jsonParser = new JSONParser();

    /**
     * Assigns the websocketUrl to use to websocketUrl by reading websocketUrlConfig;
     */
    private void generateWebsocketUrl(Participant participant)
        throws org.json.simple.parser.ParseException
    {
        if (!supportsLanguageRouting())
        {
            websocketUrl = websocketUrlConfig;
            return;
        }

        org.json.simple.parser.JSONParser jsonParser = new org.json.simple.parser.JSONParser();
        Object obj = jsonParser.parse(websocketUrlConfig);
        org.json.simple.JSONObject languageMap = (org.json.simple.JSONObject) obj;
        String language = participant.getSourceLanguage() != null ? participant.getSourceLanguage() : "en";
        Object urlObject = languageMap.get(language);
        if (!(urlObject instanceof String))
        {
            logger.error("No websocket URL configured for language " + language);
            websocketUrl = null;
            return;
        }
        websocketUrl = (String) urlObject;
    }

    /**
     * Create a TranscriptionService which will send audio to the VOSK service
     * platform to get a transcription
     */
    public VoskTranscriptionService()
    {
        websocketUrlConfig = JigasiBundleActivator.getConfigurationService()
                .getString(WEBSOCKET_URL, DEFAULT_WEBSOCKET_URL);
    }

    /**
     * No configuration required yet
     */
    public boolean isConfiguredProperly()
    {
        return true;
    }

    /**
     * If the websocket url is a JSON, language routing is supported
     */
    public boolean supportsLanguageRouting()
    {
        return websocketUrlConfig.trim().startsWith("{");
    }

    /**
     * Sends audio as an array of bytes to Vosk service
     *
     * @param request        the TranscriptionRequest which holds the audio to be sent
     * @param resultConsumer a Consumer which will handle the
     *                       TranscriptionResult
     */
    @Override
    public void sendSingleRequest(final TranscriptionRequest request,
                                  final Consumer<TranscriptionResult> resultConsumer)
    {
        // Try to create the client, which can throw an IOException
        try
        {
            // Set the sampling rate and encoding of the audio
            AudioFormat format = request.getFormat();
            if (!format.getEncoding().equals("LINEAR"))
            {
                throw new IllegalArgumentException("Given AudioFormat" +
                        "has unexpected" +
                        "encoding");
            }
            Instant timeRequestReceived = Instant.now();

            WebSocketClient ws = new WebSocketClient();
            VoskWebsocketSession socket = new VoskWebsocketSession(request);
            ws.start();
            ws.connect(socket, new URI(websocketUrl));
            socket.awaitClose();
            resultConsumer.accept(
                    new TranscriptionResult(
                            null,
                            UUID.randomUUID(),
                            timeRequestReceived,
                            false,
                            request.getLocale().toLanguageTag(),
                            0,
                            new TranscriptionAlternative(socket.getResult())));
        }
        catch (Exception e)
        {
            logger.error("Error sending single req", e);
        }
    }

    @Override
    public StreamingRecognitionSession initStreamingSession(Participant participant)
        throws UnsupportedOperationException
    {
        try
        {
            generateWebsocketUrl(participant);
            VoskWebsocketStreamingSession streamingSession = new VoskWebsocketStreamingSession(
                    participant.getDebugName(), participant);
            streamingSession.transcriptionTag = participant.getTranslationLanguage();
            if (streamingSession.transcriptionTag == null)
            {
                streamingSession.transcriptionTag = participant.getSourceLanguage();
            }
            return streamingSession;
        }
        catch (Exception e)
        {
            throw new UnsupportedOperationException("Failed to create streaming session", e);
        }
    }

    @Override
    public boolean supportsFragmentTranscription()
    {
        return true;
    }

    @Override
    public boolean supportsStreamRecognition()
    {
        return true;
    }

    /**
     * A Transcription session for transcribing streams, handles
     * the lifecycle of websocket
     */
    @WebSocket
    public class VoskWebsocketStreamingSession
        implements StreamingRecognitionSession
    {
        private Session session;
        /* The name of the participant */
        private final String debugName;
        /* The participant object for accessing additional information */
        private final Participant participant;
        /* The sample rate of the audio stream we collect from the first request */
        private double sampleRate = -1.0;
        /* Last returned result so we do not return the same string twice */
        private String lastResult = "";
        /* Transcription language requested by the user who requested the transcription */
        private String transcriptionTag = "en-US";

        /**
         * List of TranscriptionListeners which will be notified when a
         * result comes in
         */
        private final List<TranscriptionListener> listeners = new ArrayList<>();

        /**
         *  Latest assigned UUID to a transcription result.
         *  A new one has to be generated whenever a definitive result is received.
         */
        private UUID uuid = UUID.randomUUID();

         VoskWebsocketStreamingSession(String debugName, Participant participant)
            throws Exception
        {
            this.debugName = debugName;
            this.participant = participant;
            WebSocketClient ws = new WebSocketClient();
            ws.start();
            ws.connect(this, new URI(websocketUrl));
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            logger.warn("STT WebSocket connection closed for participant " + debugName + 
                       ". Status: " + statusCode + ", Reason: " + (reason != null ? reason : "Unknown"));
            this.session = null;
            
            // Notify participant about connection loss for potential retry
            if (participant != null && !participant.isCompleted()) {
                logger.info("STT connection lost for participant " + debugName + 
                           " (Status: " + statusCode + "). Will retry on next audio data.");
                
                // Reset retry counter for immediate reconnection attempt
                participant.resetSttRetryCount();
            } else if (participant != null && participant.isCompleted()) {
                logger.debug("Participant " + debugName + " has left - skipping STT reconnection");
            }
        }

        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            this.session = session;
        }

        @OnWebSocketMessage
        public void onMessage(String msg)
        {
            try
            {
                this.onMessageInternal(msg);
            }
            catch (ParseException e)
            {
                logger.error("Error parsing message: " + msg, e);
            }
        }

        private void onMessageInternal(String msg)
            throws ParseException
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(debugName + "Received response: " + msg);
            }

            boolean partial = true;
            String result = "";
            JSONObject obj = (JSONObject)jsonParser.parse(msg);
            if (obj.containsKey("partial"))
            {
                result = (String)obj.get("partial");
            }
            else
            {
                partial = false;
                result = (String)obj.get("text");
            }

            if (!result.isEmpty() && (!partial || !result.equals(lastResult)))
            {
                lastResult = result;
                for (TranscriptionListener l : listeners)
                {
                    l.notify(new TranscriptionResult(
                            null,
                            uuid,
                            // this time needs to be the one when the audio was sent
                            // the results need to be matched with the time when we sent the audio, so we have
                            // the real time when this transcription was started
                            Instant.now(),
                            partial,
                            transcriptionTag,
                            1.0,
                            new TranscriptionAlternative(result)));
                }
            }

            if (!partial)
            {
                this.uuid = UUID.randomUUID();
            }
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            // Log error with more context
            String errorType = cause.getClass().getSimpleName();
            String errorMessage = cause.getMessage() != null ? cause.getMessage() : "Unknown error";
            
            logger.error("STT WebSocket error for participant " + debugName + 
                        " [Type: " + errorType + ", Message: " + errorMessage + "]", cause);
            
            // Mark session as null to trigger reconnection attempt
            this.session = null;
            
            // Notify participant about connection loss for potential retry
            if (participant != null && !participant.isCompleted()) {
                logger.info("STT connection lost for participant " + debugName + 
                           " due to " + errorType + ". Will retry on next audio data.");
                
                // Reset retry counter to allow immediate reconnection attempt
                participant.resetSttRetryCount();
            } else if (participant != null && participant.isCompleted()) {
                logger.debug("Participant " + debugName + " has left - skipping STT reconnection");
            }
        }

        public void sendRequest(TranscriptionRequest request)
        {
            // Check if session is still active before sending
            if (session == null || !session.isOpen())
            {
                logger.warn("STT session is not available for participant " + debugName + 
                           ". Session will be recreated on next audio data.");
                return; // Skip this audio packet - session will be recreated by participant
            }
            
            try
            {
                if (sampleRate < 0)
                {
                    sampleRate = request.getFormat().getSampleRate();
                    
                    // Build config JSON with user information
                    StringBuilder configJson = new StringBuilder();
                    configJson.append("{\"config\" : {");
                    configJson.append("\"sample_rate\" : ").append(sampleRate);
                    
                    // Add participant information from debugName (format: roomId/participantName)
                    if (debugName != null && !debugName.isEmpty()) {
                        configJson.append(", \"debug_name\" : \"").append(debugName).append("\"");
                        
                        // Extract room_id from debugName
                        String[] parts = debugName.split("/");
                        if (parts.length >= 2) {
                            configJson.append(", \"room_id\" : \"").append(parts[0]).append("\"");
                            
                            // participant_id는 기존처럼 debugName에서 추출한 participant name 사용
                            String participantId = parts[1];
                            configJson.append(", \"participant_id\" : \"").append(participantId).append("\"");
                        }
                    }
                    
                    // Add language if available
                    if (transcriptionTag != null && !transcriptionTag.isEmpty()) {
                        configJson.append(", \"language\" : \"").append(transcriptionTag).append("\"");
                    }
                    
                    // Add participant information
                    if (participant != null) {
                        // Add moderator information
                        configJson.append(", \"is_moderator\" : ").append(participant.isModerator());
                        
                        // Add role information
                        String role = participant.getChatMemberRole();
                        if (role != null && !role.isEmpty()) {
                            configJson.append(", \"role\" : \"").append(role).append("\"");
                        }
                        
                        // Add stats_id as separate field if available
                        if (participant.getStatsId() != null && !participant.getStatsId().isEmpty()) {
                            configJson.append(", \"stats_id\" : \"").append(participant.getStatsId()).append("\"");
                        }
                        
                        if (logger.isDebugEnabled()) {
                            logger.debug("Participant info for Vosk - ID: " + participant.getDebugName() + 
                                       ", is_moderator: " + participant.isModerator() + 
                                       ", role: " + role + 
                                       ", stats_id: " + participant.getStatsId());
                        }
                    }
                    
                    configJson.append("}}");
                    
                    String configJsonStr = configJson.toString();
                    if (logger.isDebugEnabled()) {
                        logger.debug("Sending config to Vosk: " + configJsonStr);
                    }
                    session.getRemote().sendString(configJsonStr);
                }
                ByteBuffer audioBuffer = ByteBuffer.wrap(request.getAudio());
                session.getRemote().sendBytes(audioBuffer);
            }
            catch (Exception e)
            {
                String errorType = e.getClass().getSimpleName();
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                
                logger.error("Error sending WebSocket request for participant " + debugName + 
                           " [Type: " + errorType + ", Message: " + errorMessage + "]", e);
                
                // Mark session as null to trigger reconnection on next attempt
                this.session = null;
                
                // Notify participant about send failure
                if (participant != null && !participant.isCompleted()) {
                    logger.info("STT send failed for participant " + debugName + 
                               " due to " + errorType + ". Will retry on next audio data.");
                    participant.resetSttRetryCount();
                } else if (participant != null && participant.isCompleted()) {
                    logger.debug("Participant " + debugName + " has left - skipping STT reconnection");
                }
            }
        }

        public void addTranscriptionListener(TranscriptionListener listener)
        {
            listeners.add(listener);
        }

        public void end()
        {
            try
            {
                session.getRemote().sendString(EOF_MESSAGE);
            }
            catch (Exception e)
            {
                logger.error("Error to finalize websocket connection for participant " + debugName, e);
            }
        }

        public boolean ended()
        {
            return session == null || !session.isOpen();
        }
    }

    /**
     * Session to send websocket data and recieve results. Non-streaming version
     */
    @WebSocket
    public class VoskWebsocketSession
    {
        /* Signal for the end of operation */
        private final CountDownLatch closeLatch;

        /* Request we need to process */
        private final TranscriptionRequest request;

        /* Collect results*/
        private StringBuilder result;

        VoskWebsocketSession(TranscriptionRequest request)
        {
            this.closeLatch = new CountDownLatch(1);
            this.request = request;
            this.result = new StringBuilder();
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            this.closeLatch.countDown(); // trigger latch
        }

        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            try
            {
                AudioFormat format = request.getFormat();
                session.getRemote().sendString("{\"config\" : {\"sample_rate\" : " + format.getSampleRate() + "}}");
                ByteBuffer audioBuffer = ByteBuffer.wrap(request.getAudio());
                session.getRemote().sendBytes(audioBuffer);
                session.getRemote().sendString(EOF_MESSAGE);
            }
            catch (IOException e)
            {
                logger.error("Error to transcribe audio", e);
            }
        }

        @OnWebSocketMessage
        public void onMessage(String msg)
        {
            result.append(msg);
            result.append('\n');
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            logger.error("Websocket connection error", cause);
        }

        public String getResult()
        {
            return result.toString();
        }

        void awaitClose()
            throws InterruptedException
        {
            closeLatch.await();
        }
    }

}
