/*
 * ENTRADA, a big data platform for network data analytics
 *
 * Copyright (C) 2016 SIDN [https://www.sidn.nl]
 * 
 * This file is part of ENTRADA.
 * 
 * ENTRADA is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * ENTRADA is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with ENTRADA. If not, see
 * [<http://www.gnu.org/licenses/].
 *
 */
package nl.sidnlabs.entrada.enrich.resolver;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import javax.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.jboss.netty.handler.ipfilter.IpSubnet;
import org.springframework.beans.factory.annotation.Value;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.log4j.Log4j2;

@Log4j2
public abstract class AbstractResolverCheck implements DnsResolverCheck {

  private static final int BLOOMFILTER_SIZE = 100000;

  private List<IpSubnet> matchers4 = new ArrayList<>();
  private List<IpSubnet> matchers6 = new ArrayList<>();

  /*
   * Two bloomfilters are used to track addresses that have been a match or a non-match. if an
   * address is not in any of the filters then do the normal lookup.
   */
  BloomFilter<String> matchFilter =
      BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), BLOOMFILTER_SIZE, 0.01);

  BloomFilter<String> nonMatchFilter =
      BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), BLOOMFILTER_SIZE, 0.01);

  @Value("${entrada.location.work}")
  private String workDir;

  @PostConstruct
  public void init() {

    String filename = workDir + System.getProperty("file.separator") + getFilename();
    File file = new File(filename);
    // read from local file
    if (!readFromFile(file)) {
      log.info("File {} cannot be read, reload from source as backup", file);
      update(file);
    }
  }

  private void update(File file) {
    // load new subnets from source
    List<String> subnets = fetch();
    if (!subnets.isEmpty()) {
      // create internal bitsubnets for camparissions
      subnets
          .stream()
          .filter(s -> s.contains("."))
          .map(this::subnetFor)
          .filter(Objects::nonNull)
          .forEach(s -> matchers4.add(s));

      subnets
          .stream()
          .filter(s -> s.contains(":"))
          .map(this::subnetFor)
          .filter(Objects::nonNull)
          .forEach(s -> matchers6.add(s));

      // write subnets to file so we do not need to get them from source every time the app starts
      writeToFile(subnets, file);
    }
    log.info("Loaded {} resolver addresses from file: {}", getSize(), file);
  }

  private IpSubnet subnetFor(String address) {
    try {
      return new IpSubnet(address);
    } catch (UnknownHostException e) {
      log.error("Cannot create subnet for: {}", address, e);
    }

    return null;
  }

  protected abstract List<String> fetch();

  private boolean readFromFile(File file) {
    log.info("Load resolver addresses from file: " + file);

    // if file does not exist or was update last on previous day, then update resolvers ip's
    if (!file.exists()) {
      return false;
    }

    Date lastModifiedDate = new Date(file.lastModified());
    Date currentDate = new Date();

    if (!DateUtils.isSameDay(lastModifiedDate, currentDate)) {
      if (log.isDebugEnabled()) {
        log.debug("File {} is too old, do not use it.", file);
      }
      return false;
    }

    List<String> lines;
    try {
      lines = Files.readAllLines(file.toPath());
    } catch (Exception e) {
      log.error("Error while reading file: {}", file, e);
      return false;
    }

    lines
        .stream()
        .filter(s -> s.contains("."))
        .map(this::subnetFor)
        .filter(Objects::nonNull)
        .forEach(s -> matchers4.add(s));

    lines
        .stream()
        .filter(s -> s.contains(":"))
        .map(this::subnetFor)
        .filter(Objects::nonNull)
        .forEach(s -> matchers6.add(s));

    log.info("Loaded {} resolver addresses from file: {}", getSize(), file);

    return !matchers4.isEmpty() || !matchers6.isEmpty();
  }

  private void writeToFile(List<String> subnets, File file) {
    try {
      Files.write(file.toPath(), subnets, CREATE, TRUNCATE_EXISTING);
    } catch (IOException e) {
      log.error("Problem while writing to file: {}", file);
    }
  }

  protected abstract String getFilename();

  @Override
  public boolean match(String address) {
    // use 2 bloomfilter to do faster check for addresses that have been seen before

    if (!matchFilter.mightContain(address) && !nonMatchFilter.mightContain(address)) {
      // new address is not matched before as a match or a non-match

      // matchers are split into a v4 and v6 group
      // to allow us to quickly skip the version we don't need
      // to check

      if (StringUtils.contains(address, ".")) {
        for (IpSubnet sn : matchers4) {
          if (subnetContains(sn, address)) {
            matchFilter.put(address);
            return true;
          }
        }

        nonMatchFilter.put(address);
        return false;
      }

      for (IpSubnet sn : matchers6) {
        if (subnetContains(sn, address)) {
          matchFilter.put(address);
          return true;
        }
      }
      nonMatchFilter.put(address);
      return false;
    }

    // address has been seen before it must be a match or non-match

    // check if it may be in the matchFilter AND DEFINITELY NOT in the nonMatchFilter
    // then it is a guaranteed match
    return matchFilter.mightContain(address) && !nonMatchFilter.mightContain(address);
  }

  private boolean subnetContains(IpSubnet subnet, String address) {
    try {
      return subnet.contains(address);
    } catch (UnknownHostException e) {
      // ignore
      if (log.isDebugEnabled()) {
        log.debug("Subnet {} contains check for {} failed", subnet, address);
      }
    }

    return false;
  }

  @Override
  public int getSize() {
    return matchers4.size() + matchers6.size();
  }

}
