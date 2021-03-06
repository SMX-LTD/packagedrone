/*******************************************************************************
 * Copyright (c) 2015, 2016 IBH SYSTEMS GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBH SYSTEMS GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.packagedrone.repo.api.internal.upload;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.packagedrone.repo.MetaKey;
import org.eclipse.packagedrone.repo.channel.ArtifactInformation;
import org.eclipse.packagedrone.repo.channel.ChannelNotFoundException;
import org.eclipse.packagedrone.repo.channel.ChannelService;
import org.eclipse.packagedrone.repo.channel.ChannelService.By;
import org.eclipse.packagedrone.repo.channel.ModifiableChannel;
import org.eclipse.packagedrone.repo.channel.servlet.AbstractChannelServiceServlet;
import org.eclipse.scada.utils.ExceptionHelper;

/**
 * Upload by {@code/channel/<channel>/<artifactName>?ns:key=value} or
 * {@code /channel/<channel>/<parentArtifactId>/<artifactName>?ns:key=value}
 */
public class UploadServletV2 extends AbstractChannelServiceServlet
{
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet ( final HttpServletRequest request, final HttpServletResponse response ) throws ServletException, IOException
    {
        response.sendError ( HttpServletResponse.SC_METHOD_NOT_ALLOWED, "The GET method is not allowed for the Upload service. Use POST or PUT instead." );
    }

    @Override
    protected void doPut ( final HttpServletRequest request, final HttpServletResponse response ) throws ServletException, IOException
    {
        processUpload ( request, response );
    }

    @Override
    protected void doPost ( final HttpServletRequest req, final HttpServletResponse resp ) throws ServletException, IOException
    {
        processUpload ( req, resp );
    }

    private void processUpload ( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException
    {
        String path = req.getPathInfo ();

        path = path.replaceFirst ( "^/+", "" );
        path = path.replaceFirst ( "/+$", "" );

        final String[] toks = path.split ( "/", 3 );

        if ( toks.length == 0 )
        {
            // handle error: missing target specifier
            sendResponse ( resp, HttpServletResponse.SC_BAD_REQUEST, "No target" );
            return;
        }
        if ( toks.length == 1 )
        {
            // handle error: missing target
            sendResponse ( resp, HttpServletResponse.SC_BAD_REQUEST, "Missing target" );
            return;
        }
        else if ( toks.length == 2 )
        {
            // handle error: missing name
            sendResponse ( resp, HttpServletResponse.SC_BAD_REQUEST, "Missing artifact name" );
            return;
        }

        final String targetType = toks[0];

        switch ( targetType )
        {
            case "channel":
                if ( toks.length <= 3 )
                {
                    processChannel ( req, resp, toks[1], ( request, response, channel ) -> {
                        return store ( channel, null, toks[2], request, response );
                    } );
                }
                else
                {
                    processChannel ( req, resp, toks[1], ( request, response, channel ) -> {
                        return store ( channel, toks[2], toks[3], request, response );
                    } );
                }
                break;
            default:
                sendResponse ( resp, HttpServletResponse.SC_BAD_REQUEST, "Unkown target type: " + targetType );
                break;
        }
    }

    private interface UploadProcessor
    {
        public String process ( HttpServletRequest request, HttpServletResponse response, ModifiableChannel channel ) throws Exception;
    }

    private void processChannel ( final HttpServletRequest req, final HttpServletResponse resp, final String channelIdOrName, final UploadProcessor processor ) throws IOException
    {
        // process

        resp.setContentType ( "text/plain" );

        final ChannelService service = getService ( req );

        final By by = By.nameOrId ( channelIdOrName );

        if ( !authenticate ( by, req, resp ) )
        {
            return;
        }

        try
        {
            final String result = service.accessCall ( by, ModifiableChannel.class, channel -> processor.process ( req, resp, channel ) );
            sendResponse ( resp, HttpServletResponse.SC_OK, result != null ? result : "" );
        }
        catch ( final ChannelNotFoundException e )
        {
            sendResponse ( resp, HttpServletResponse.SC_NOT_FOUND, String.format ( "Unable to find channel: %s", channelIdOrName ) );
            return;
        }
        catch ( final Exception e )
        {
            final Throwable cause = ExceptionHelper.getRootCause ( e );
            int state = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            if ( cause instanceof IllegalArgumentException )
            {
                state = HttpServletResponse.SC_BAD_REQUEST;
            }
            sendResponse ( resp, state, ExceptionHelper.getMessage ( e ) );
        }
    }

    private static void sendResponse ( final HttpServletResponse response, final int status, final String message ) throws IOException
    {
        response.setStatus ( status );
        response.setContentType ( "text/plain" );
        response.getWriter ().println ( message );
    }

    private static String store ( final ModifiableChannel channel, final String parentArtifactId, final String name, final HttpServletRequest request, final HttpServletResponse response ) throws IOException
    {
        try
        {
            final ArtifactInformation art = channel.getContext ().createArtifact ( parentArtifactId, request.getInputStream (), name, makeMetaData ( request ) );
            response.setStatus ( HttpServletResponse.SC_OK );
            if ( art != null )
            {
                // no veto
                return art.getId ();
            }
            return null; // veto
        }
        catch ( final IllegalArgumentException e )
        {
            throw new RuntimeException ( e );
        }
    }

    static Map<MetaKey, String> makeMetaData ( final HttpServletRequest request )
    {
        final Map<MetaKey, String> result = new HashMap<> ();

        for ( final Map.Entry<String, String[]> entry : request.getParameterMap ().entrySet () )
        {
            final MetaKey key = MetaKey.fromString ( entry.getKey () );
            if ( key == null )
            {
                throw new IllegalArgumentException ( String.format ( "Invalid meta data key format: %s", entry.getKey () ) );
            }

            final String[] values = entry.getValue ();
            if ( values == null || values.length < 1 )
            {
                continue;
            }

            result.put ( key, values[0] );
        }

        return result;
    }
}
