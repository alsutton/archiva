package org.apache.archiva.rest.services;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.common.utils.VersionUtil;
import org.apache.archiva.maven.model.Artifact;
import org.apache.archiva.metadata.model.ArtifactMetadata;
import org.apache.archiva.metadata.model.facets.AuditEvent;
import org.apache.archiva.metadata.repository.MetadataRepository;
import org.apache.archiva.metadata.repository.MetadataRepositoryException;
import org.apache.archiva.metadata.repository.RepositorySession;
import org.apache.archiva.filter.Filter;
import org.apache.archiva.filter.IncludesFilter;
import org.apache.archiva.repository.ReleaseScheme;
import org.apache.archiva.repository.Repository;
import org.apache.archiva.repository.RepositoryNotFoundException;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.rest.api.services.ArchivaRestServiceException;
import org.apache.archiva.rest.api.services.MergeRepositoriesService;
import org.apache.archiva.stagerepository.merge.RepositoryMerger;
import org.apache.archiva.stagerepository.merge.RepositoryMergerException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Olivier Lamy
 * @since 1.4-M3
 */
@Service ( "mergeRepositoriesService#rest" )
public class DefaultMergeRepositoriesService
    extends AbstractRestService
    implements MergeRepositoriesService
{

    // FIXME check archiva-merge-repository to sourceRepoId

    @Inject
    private List<RepositoryMerger> repositoryMerger;

    @Inject
    private RepositoryRegistry repositoryRegistry;


    @Override
    public List<Artifact> getMergeConflictedArtifacts( String sourceRepositoryId, String targetRepositoryId )
        throws ArchivaRestServiceException
    {
        // reading conflicts back means reading the target's metadata, so the caller needs rights on it too
        checkTargetIsMergeable( targetRepositoryId );

        RepositorySession repositorySession = null;
        try
        {
            repositorySession = repositorySessionFactory.createSession();
        }
        catch ( MetadataRepositoryException e )
        {
            log.error( "Error while creating repository session {}", e.getMessage( ), e );
        }
        try
        {
            RepositoryMerger merger = findMerger( sourceRepositoryId );
            List<ArtifactMetadata> artifactMetadatas =
                merger.getConflictingArtifacts( repositorySession.getRepository(), sourceRepositoryId,
                                                          targetRepositoryId );

            return buildArtifacts( artifactMetadatas, sourceRepositoryId );
        }
        catch ( RepositoryMergerException | RepositoryNotFoundException e )
        {
            throw new ArchivaRestServiceException( e.getMessage(), e );
        }
        finally
        {
            repositorySession.close();
        }
    }

    RepositoryMerger findMerger(String repositoryId) throws RepositoryNotFoundException
    {
        Repository repo = repositoryRegistry.getRepository( repositoryId );
        if (repo==null) {
            throw new RepositoryNotFoundException( repositoryId );
        } else {
            return repositoryMerger.stream( ).filter( m -> m.supportsRepository( repo.getType( ) ) ).findFirst().get();
        }
    }

    @Override
    public void mergeRepositories( String sourceRepositoryId, String targetRepositoryId, boolean skipConflicts )
        throws ArchivaRestServiceException
    {
        // the annotation can only bind one resource, and it binds the source: without this the target of a
        // merge is never authorized, so managing any repository is enough to write into every other one
        checkTargetIsMergeable( targetRepositoryId );

        try
        {
            RepositoryMerger merger = findMerger( sourceRepositoryId );

            if ( skipConflicts )
            {
                mergeBySkippingConflicts( merger,  sourceRepositoryId, targetRepositoryId );
            }
            else
            {
                doMerge( merger, sourceRepositoryId, targetRepositoryId );
            }

        }
        catch ( RepositoryMergerException | RepositoryNotFoundException e )
        {
            throw new ArchivaRestServiceException( e.getMessage(), e );
        }

    }


    /**
     * A merge writes into the target repository and triggers a scan of it, so the caller needs write rights on the
     * target as well as merge rights on the source. The {@code @RedbackAuthorization} annotation binds a single
     * resource and binds it to the source, so the target has to be checked here.
     */
    protected void checkTargetIsMergeable( String targetRepositoryId )
        throws ArchivaRestServiceException
    {
        if ( StringUtils.isEmpty( targetRepositoryId ) )
        {
            throw new ArchivaRestServiceException( "merge.repositories.target.missing",
                                                   Response.Status.BAD_REQUEST.getStatusCode(), null );
        }

        if ( !getManageableRepos().contains( targetRepositoryId ) )
        {
            throw new ArchivaRestServiceException( "merge.repositories.target.denied",
                                                   Response.Status.FORBIDDEN.getStatusCode(), null );
        }
    }

    protected void doMerge( RepositoryMerger merger, String sourceRepositoryId, String targetRepositoryId )
        throws RepositoryMergerException, ArchivaRestServiceException
    {
        RepositorySession repositorySession = null;
        try
        {
            repositorySession = repositorySessionFactory.createSession();
        }
        catch ( MetadataRepositoryException e )
        {
            e.printStackTrace( );
        }

        try
        {
            org.apache.archiva.repository.ManagedRepository managedRepo = repositoryRegistry.getManagedRepository(targetRepositoryId);
            MetadataRepository metadataRepository = repositorySession.getRepository();
            List<ArtifactMetadata> sourceArtifacts = metadataRepository.getArtifacts(repositorySession , sourceRepositoryId );

            if ( managedRepo.getActiveReleaseSchemes().contains(ReleaseScheme.RELEASE) && !managedRepo.getActiveReleaseSchemes().contains(ReleaseScheme.SNAPSHOT) )
            {
                mergeWithOutSnapshots(merger, metadataRepository, sourceArtifacts, sourceRepositoryId, targetRepositoryId );
            }
            else
            {
                merger.merge( metadataRepository, sourceRepositoryId, targetRepositoryId );

                for ( ArtifactMetadata metadata : sourceArtifacts )
                {
                    triggerAuditEvent( targetRepositoryId, metadata.getId(), AuditEvent.MERGING_REPOSITORIES );
                }
            }

            doScanRepository( targetRepositoryId, false );
        }
        catch ( MetadataRepositoryException e )
        {
            throw new ArchivaRestServiceException( e.getMessage(), e );
        } finally
        {
            repositorySession.close();
        }
    }

    private void mergeBySkippingConflicts( RepositoryMerger merger, String sourceRepositoryId, String targetRepositoryId )
        throws RepositoryMergerException, ArchivaRestServiceException
    {

        RepositorySession repositorySession = null;
        try
        {
            repositorySession = repositorySessionFactory.createSession();
        }
        catch ( MetadataRepositoryException e )
        {
            e.printStackTrace( );
        }
        try
        {
            List<ArtifactMetadata> conflictSourceArtifacts =
                merger.getConflictingArtifacts( repositorySession.getRepository(), sourceRepositoryId,
                                                          targetRepositoryId );
            MetadataRepository metadataRepository = repositorySession.getRepository();
            List<ArtifactMetadata> sourceArtifacts = metadataRepository.getArtifacts(repositorySession , sourceRepositoryId );
            sourceArtifacts.removeAll( conflictSourceArtifacts );

            org.apache.archiva.repository.ManagedRepository managedRepo = repositoryRegistry.getManagedRepository(targetRepositoryId);

            if ( managedRepo.getActiveReleaseSchemes().contains(ReleaseScheme.RELEASE) && !managedRepo.getActiveReleaseSchemes().contains(ReleaseScheme.SNAPSHOT))
            {
                mergeWithOutSnapshots( merger, metadataRepository, sourceArtifacts, sourceRepositoryId, targetRepositoryId );
            }
            else
            {

                Filter<ArtifactMetadata> artifactsWithOutConflicts =
                    new IncludesFilter<ArtifactMetadata>( sourceArtifacts );
                merger.merge( metadataRepository, sourceRepositoryId, targetRepositoryId,
                                        artifactsWithOutConflicts );
                for ( ArtifactMetadata metadata : sourceArtifacts )
                {
                    triggerAuditEvent( targetRepositoryId, metadata.getId(), AuditEvent.MERGING_REPOSITORIES );
                }
            }

            doScanRepository( targetRepositoryId, false );
        }
        catch ( MetadataRepositoryException e )
        {
            throw new ArchivaRestServiceException( e.getMessage(), e );
        } finally
        {
            repositorySession.close();
        }
    }

    private void mergeWithOutSnapshots( RepositoryMerger merger, MetadataRepository metadataRepository, List<ArtifactMetadata> sourceArtifacts,
                                        String sourceRepoId, String repoid )
        throws RepositoryMergerException
    {
        List<ArtifactMetadata> artifactsWithOutSnapshots = new ArrayList<>();
        for ( ArtifactMetadata metadata : sourceArtifacts )
        {
            if ( VersionUtil.isSnapshot( metadata.getProjectVersion() ) )
            //if ( metadata.getProjectVersion().contains( VersionUtil.SNAPSHOT ) )
            {
                artifactsWithOutSnapshots.add( metadata );
            }
            else
            {
                triggerAuditEvent( repoid, metadata.getId(), AuditEvent.MERGING_REPOSITORIES );
            }

        }
        sourceArtifacts.removeAll( artifactsWithOutSnapshots );

        Filter<ArtifactMetadata> artifactListWithOutSnapShots = new IncludesFilter<ArtifactMetadata>( sourceArtifacts );
        merger.merge( metadataRepository, sourceRepoId, repoid, artifactListWithOutSnapShots );
    }
}
