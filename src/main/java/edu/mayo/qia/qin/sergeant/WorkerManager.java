package edu.mayo.qia.qin.sergeant;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.codahale.metrics.health.HealthCheck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TreeTraversingParser;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Path("/")
public class WorkerManager extends HealthCheck implements Job {
  static Logger logger = LoggerFactory.getLogger(WorkerManager.class);
  private Map<String, Worker> workerMap = new ConcurrentHashMap<String, Worker>();

  public WorkerManager() {
  }

  public WorkerManager(List<Worker> workers) {
    updateWorkers(workers);
  }

  public void updateWorkers(List<Worker> workers) {
    List<String> workerNames = new ArrayList<String>();
    for (Worker worker : workers) {
      workerNames.add(worker.endPoint);
      workerMap.put(worker.endPoint, worker);
    }
    for (String key : workerMap.keySet()) {
      if (!workerNames.contains(key)) {
        workerMap.remove(key);
      }
    }
    for (Worker worker : workers) {
      worker.formCurl();
    }
  }

  @GET
  @Path("/service")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getServiceInfo() {
    return Response.ok(workerMap).build();
  }

  @Path("/service/{endpoint}")
  public Worker get(@PathParam("endpoint") String endPoint) {
    Worker w = workerMap.get(endPoint);
    return w;
  }

  @GET
  @Path("/job")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJob() {
    return Response.ok(Sergeant.jobs.values()).build();
  }

  @GET
  @Path("/job/{uuid}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJobInfo(@PathParam("uuid") String uuid) {
    if (Sergeant.jobs.containsKey(uuid)) {
      return Response.ok(Sergeant.jobs.get(uuid)).build();
    } else {
      return Response.status(Status.NOT_FOUND).build();
    }
  }

  @GET
  @Path("/job/{uuid}/{file}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJobInfo(@PathParam("uuid") String uuid, @PathParam("file") String file) {
    if (Sergeant.jobs.containsKey(uuid)) {
      JobInfo job = Sergeant.jobs.get(uuid);
      if (job.fileMap.containsKey(file)) {
        try {
          return Response.ok(new FileInputStream(job.fileMap.get(file))).build();
        } catch (FileNotFoundException e) {
          return Response.status(Status.NOT_FOUND).build();
        }
      }
    }
    return Response.status(Status.NOT_FOUND).build();
  }

  @DELETE
  @Path("/job/{uuid}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response delete(@PathParam("uuid") String uuid) {
    if (Sergeant.jobs.containsKey(uuid)) {
      JobInfo job = Sergeant.jobs.get(uuid);
      try {
        job.shutdown();
      } catch (Exception e) {
        logger.error("Failed to shutdown the job: " + uuid, e);
      }
      Sergeant.jobs.remove(uuid);
      return Response.ok(Sergeant.jobs.get(uuid)).build();
    } else {
      return Response.status(Status.NOT_FOUND).build();
    }
  }

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    // Parse the config file
    try (InputStream input = new FileInputStream(Sergeant.configFile)) {
      ObjectMapper mapper = Sergeant.environment.getObjectMapper();

      final JsonNode node = mapper.readTree(new YAMLFactory().createParser(input));
      final SergeantConfiguration config = mapper.readValue(new TreeTraversingParser(node), SergeantConfiguration.class);
      if (config != null) {
        Sergeant.workerManager.updateWorkers(config.services);
      }
    } catch (Exception e) {
      Sergeant.logger.error("Error parsing config file", e);
    }
  }

  @Override
  protected Result check() throws Exception {
    if (workerMap.isEmpty()) {
      return Result.unhealthy("No workers defined");
    } else {
      return Result.healthy();
    }
  }
}
