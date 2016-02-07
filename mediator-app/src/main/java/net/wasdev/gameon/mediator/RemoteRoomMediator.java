/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.gameon.mediator;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Level;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import net.wasdev.gameon.mediator.ConnectionUtils.Drain;
import net.wasdev.gameon.mediator.MapClient.ConnectionDetails;
import net.wasdev.gameon.mediator.MapClient.Exit;
import net.wasdev.gameon.mediator.MapClient.Site;

/**
 * Mediator for connections to remote rooms. This manages creating and
 * destroying the client WebSocket connection, and the queue of outgoing
 * messages that need to be sent.
 */
public class RemoteRoomMediator implements RoomMediator {

    /**
     * Information about the remote endpoint. Used to construct the client
     * websocket
     */
    private final String roomName;
    private final String roomFullName;
    private final String id;
    private final ConnectionDetails details;

    /**
     * Connection utilities for using the websocket. Passed in by the
     * {@code PlayerConnectionMediator}, which manages the lifecycle of this
     * mediator.
     */
    private final ConnectionUtils connectionUtils;

    /** The session for the established websocket connection. */
    private Session roomSession;

    /** The owning mediator: manages the connection to the client device. */
    private volatile PlayerConnectionMediator playerMediator;

    /** Drain used to send messages to the room */
    private Drain drainToRoom = null;

    /** Queue of messages destined for the room */
    private final LinkedBlockingDeque<RoutedMessage> toRoom = new LinkedBlockingDeque<RoutedMessage>();

    /**
     * @param room
     *            Information about the target room endpoint
     * @param connectionUtils
     *            Utilities for interacting with the outbound websocket
     */
    public RemoteRoomMediator(Site room, ConnectionUtils connectionUtils) {
        this.id = room.getId();
        this.details = room.getInfo().getConnectionDetails();
        this.roomName = room.getInfo().getName();
        this.roomFullName = room.getInfo().getFullName();
        this.connectionUtils = connectionUtils;
    }
    /**
     * @param exit
     *            Information about the target room endpoint
     * @param connectionUtils
     *            Utilities for interacting with the outbound websocket
     */
    public RemoteRoomMediator(Exit exit, ConnectionUtils connectionUtils) {
        this.id = exit.getId();
        this.details = exit.getConnectionDetails();
        this.roomName = exit.getName();
        this.roomFullName = exit.getFullName();
        this.connectionUtils = connectionUtils;
    }

    /**
     * @return the id of the target room
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * @return the name of the target room
     */
    @Override
    public String getName() {
        return roomFullName;
    }

    /**
     * Attempt to establish the connection to the remote room (if not already
     * established)
     *
     * @see net.wasdev.gameon.mediator.RoomMediator#connect()
     */
    @Override
    public boolean connect() {
        System.out.println("Asked to connect.. roomSession is null? "+(roomSession==null)+ (roomSession!=null?" roomSession is Open?"+roomSession.isOpen():""));
        
        if (roomSession != null && roomSession.isOpen()) {
            return true;
        }

        Log.log(Level.FINE, this, "Creating connection to room {0}", id);
              
        final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create()
                .decoders(Arrays.asList(RoutedMessageDecoder.class)).encoders(Arrays.asList(RoutedMessageEncoder.class))
                .build();
        
        switch(details.getType()){
            case "websocket":{
                
                Log.log(Level.FINE, this, "Creating websocket to {0}", details.getTarget());
                
                URI uriServerEP = URI.create(details.getTarget());

                try {
                    // Create the new outbound session with a programmatic endpoint
                    Session s = connectionUtils.connectToServer(new Endpoint() {

                        @Override
                        public void onOpen(Session session, EndpointConfig config) {
                            // let the room mediator know the connection was opened
                            connectionOpened(session);

                            // Add message handler
                            session.addMessageHandler(new MessageHandler.Whole<RoutedMessage>() {
                                @Override
                                public void onMessage(RoutedMessage message) {
                                    Log.log(Level.FINEST, session, "received from room {0}: {1}", getId(), message);
                                    if (playerMediator != null)
                                        playerMediator.sendToClient(message);
                                }
                            });
                        }

                        @Override
                        public void onClose(Session session, CloseReason closeReason) {
                            // let the room mediator know the connection was closed
                            connectionClosed(closeReason);
                        }

                        @Override
                        public void onError(Session session, Throwable thr) {
                            Log.log(Level.FINEST, this, "BADNESS " + session.getUserProperties(), thr);

                            connectionUtils.tryToClose(session,
                                    new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, thr.toString()));
                        }
                    }, cec, uriServerEP);
                    Log.log(Level.FINEST, s, "CONNECTED to room {0}", id);

                    return true;
                } catch (DeploymentException e) {
                    Log.log(Level.FINER, this,
                            "Deployment exception creating connection to room " + id, e);
                } catch (IOException e) {
                    Log.log(Level.FINER, this, "I/O exception creating connection to room " + id, e);
                }
                
                break;
            }
            default:{
                return false;
            }         
        }
       
        return false;
    }

    /**
     * link to the player mediator (start relaying messages)
     *
     * @param playerMediator
     */
    @Override
    public boolean subscribe(PlayerConnectionMediator playerMediator, long lastMessage) {
        this.playerMediator = playerMediator;
        return true;
    }

    /**
     * Stop relaying messages to the player.
     * 
     * @param playerMediator
     */
    @Override
    public void unsubscribe(PlayerConnectionMediator playerMediator) {
        this.playerMediator = null;
    }

    /**
     * Close the WebSocket connection to the room, clear pending messages
     *
     * @see net.wasdev.gameon.mediator.RoomMediator#disconnect(net.wasdev.gameon.mediator.PlayerConnectionMediator)
     */
    @Override
    public void disconnect() {
        connectionUtils.tryToClose(roomSession);
        toRoom.clear();
    }

    /**
     * Send a message on to the room
     *
     * @see net.wasdev.gameon.mediator.RoomMediator#send(net.wasdev.gameon.mediator.RoutedMessage)
     */
    @Override
    public void send(RoutedMessage message) {
        // make sure we're only dealing with messages for the room,
        if (message.isForRoom(this)) {
            // TODO: Capacity?
            toRoom.offer(message);
        } else {
            Log.log(Level.FINEST, this, "send -- Dropping message {0}", message);
        }
    }

    /**
     * Called when the connection to the room has been established.
     */
    private void connectionOpened(Session roomSession) {
        Log.log(Level.FINER, this, "ROOM CONNECTION OPEN {0}: {1}", id);

        this.roomSession = roomSession;

        // set up delivery thread to send messages to the room as they arrive
        drainToRoom = connectionUtils.drain("TO ROOM[" + id + "]", toRoom, roomSession);
    }

    /**
     * Called when the connection to the room has closed. If the connection
     * closed badly, try to open again.
     */
    private void connectionClosed(CloseReason reason) {
        Log.log(Level.FINER, this, "ROOM CONNECTION CLOSED {0}: {1}", id, reason);

        if (drainToRoom != null)
            drainToRoom.stop();

        if (playerMediator != null && !reason.getCloseCode().equals(CloseCodes.NORMAL_CLOSURE)) {
            connect();
        }
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[roomId=" + id + "]";
    }
}