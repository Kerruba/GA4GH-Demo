/*******************************************************************************
 * Copyright 2013 EMBL-EBI
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package net.sf.cram.index;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.cram.encoding.reader.DataReaderFactory;
import htsjdk.samtools.cram.encoding.reader.RefSeqIdReader;
import htsjdk.samtools.cram.io.DefaultBitInputStream;
import htsjdk.samtools.cram.structure.AlignmentSpan;
import htsjdk.samtools.cram.structure.CompressionHeader;
import htsjdk.samtools.cram.structure.Container;
import htsjdk.samtools.cram.structure.CramCompressionRecord;
import htsjdk.samtools.cram.structure.Slice;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class CramIndex {
	private List<Entry> entries = new ArrayList<CramIndex.Entry>();

	public void addContainer(Container c) throws IOException, IllegalArgumentException, IllegalAccessException {
		if (c.isEOF())
			return;
		for (int i = 0; i < c.slices.length; i++) {
			Slice s = c.slices[i];
			if (s.sequenceId == -2) {
				this.entries.addAll(getMutliRefEntries(c.header, s, c.offset, c.landmarks));
			} else {
				Entry e = new Entry();
				e.sequenceId = c.sequenceId;
				e.alignmentStart = s.alignmentStart;
				e.alignmentSpan = s.alignmentSpan;
				e.containerStartOffset = c.offset;
				e.sliceOffset = c.landmarks[i];
				e.sliceSize = s.size;

				e.sliceIndex = i;

				entries.add(e);
			}
		}
	}

	public void writeTo(OutputStream os) throws IOException {
		Collections.sort(entries, byStartDesc);
		for (Entry e : entries) {
			String string = e.toString();
			os.write(string.getBytes());
			os.write('\n');
		}
	}

	private static Collection<Entry> getMutliRefEntries(CompressionHeader header, Slice slice, long containerOffset,
			int[] landmarks) throws IllegalArgumentException, IllegalAccessException, IOException {
		final DataReaderFactory dataReaderFactory = new DataReaderFactory();
		final Map<Integer, InputStream> inputMap = new HashMap<Integer, InputStream>();
		for (final Integer exId : slice.external.keySet()) {
			inputMap.put(exId, new ByteArrayInputStream(slice.external.get(exId).getRawContent()));
		}

		final RefSeqIdReader reader = new RefSeqIdReader(slice.sequenceId, slice.alignmentStart,
				ValidationStringency.SILENT);
		dataReaderFactory.buildReader(reader,
				new DefaultBitInputStream(new ByteArrayInputStream(slice.coreBlock.getRawContent())), inputMap, header,
				slice.sequenceId);
		reader.APDelta = header.APDelta;

		for (int i = 0; i < slice.nofRecords; i++) {
			final CramCompressionRecord record = new CramCompressionRecord();
			record.sliceIndex = slice.index;
			record.index = i;

			reader.read();

			if (record.sequenceId == slice.sequenceId) {
				record.sequenceId = slice.sequenceId;
			} else {
				if (record.sequenceId == SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX)
					record.sequenceName = SAMRecord.NO_ALIGNMENT_REFERENCE_NAME;
			}
		}

		Map<Integer, AlignmentSpan> spans = reader.getReferenceSpans();
		List<Entry> entries = new ArrayList<CramIndex.Entry>(spans.size());
		for (int seqId : spans.keySet()) {
			Entry e = new Entry();
			e.sequenceId = seqId;
			AlignmentSpan span = spans.get(seqId);
			e.alignmentStart = span.getStart();
			e.alignmentSpan = span.getSpan();
			e.sliceSize = slice.size;
			e.sliceIndex = slice.index;

			e.containerStartOffset = containerOffset;
			e.sliceOffset = landmarks[slice.index];

			entries.add(e);
		}

		return entries;
	}

	public static class Entry implements Comparable<Entry>, Cloneable {
		public int sequenceId;
		public int alignmentStart;
		public int alignmentSpan;
		public long containerStartOffset;
		public int sliceOffset;
		public int sliceSize;
		public int sliceIndex;

		public Entry() {
		}

		public Entry(String line) {
			String[] chunks = line.split("\t");
			if (chunks.length != 6)
				throw new RuntimeException("Invalid index format: " + line);

			sequenceId = Integer.valueOf(chunks[0]);
			alignmentStart = Integer.valueOf(chunks[1]);
			alignmentSpan = Integer.valueOf(chunks[2]);
			containerStartOffset = Long.valueOf(chunks[3]);
			sliceOffset = Integer.valueOf(chunks[4]);
			sliceSize = Integer.valueOf(chunks[5]);
		}

		@Override
		public String toString() {
			return String.format("%d\t%d\t%d\t%d\t%d\t%d", sequenceId, alignmentStart, alignmentSpan,
					containerStartOffset, sliceOffset, sliceSize);
		}

		@Override
		public int compareTo(Entry o) {
			if (sequenceId != o.sequenceId)
				return o.sequenceId - sequenceId;
			if (alignmentStart != o.alignmentStart)
				return alignmentStart - o.alignmentStart;

			return (int) (containerStartOffset - o.containerStartOffset);
		}

		@Override
		public Entry clone() throws CloneNotSupportedException {
			Entry entry = new Entry();
			entry.sequenceId = sequenceId;
			entry.alignmentStart = alignmentStart;
			entry.alignmentSpan = alignmentSpan;
			entry.containerStartOffset = containerStartOffset;
			entry.sliceOffset = sliceOffset;
			entry.sliceSize = sliceSize;
			return entry;
		}
	}

	public static List<Entry> readIndex(InputStream is) {
		List<Entry> list = new LinkedList<CramIndex.Entry>();
		Scanner scanner = new Scanner(is);

		try {
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine();
				Entry entry = new Entry(line);
				list.add(entry);
			}
		} finally {
			try {
				scanner.close();
			} catch (Exception e) {
			}
		}

		return list;
	}

	private static Comparator<Entry> byEnd = new Comparator<Entry>() {

		@Override
		public int compare(Entry o1, Entry o2) {
			if (o1.sequenceId != o2.sequenceId)
				return o2.sequenceId - o1.sequenceId;
			if (o1.alignmentStart + o1.alignmentSpan != o2.alignmentStart + o2.alignmentSpan)
				return o1.alignmentStart + o1.alignmentSpan - o2.alignmentStart - o2.alignmentSpan;

			return (int) (o1.containerStartOffset - o2.containerStartOffset);
		}
	};

	private static Comparator<Entry> byStart = new Comparator<Entry>() {

		@Override
		public int compare(Entry o1, Entry o2) {
			if (o1.sequenceId != o2.sequenceId)
				return o2.sequenceId - o1.sequenceId;
			if (o1.alignmentStart != o2.alignmentStart)
				return o1.alignmentStart - o2.alignmentStart;

			return (int) (o1.containerStartOffset - o2.containerStartOffset);
		}
	};

	private static Comparator<Entry> byStartDesc = new Comparator<Entry>() {

		@Override
		public int compare(Entry o1, Entry o2) {
			if (o1.sequenceId != o2.sequenceId) {
				if (o1.sequenceId == -1)
					return 1;
				if (o2.sequenceId == -1)
					return -1;
				return -o2.sequenceId + o1.sequenceId;
			}
			if (o1.alignmentStart != o2.alignmentStart)
				return o1.alignmentStart - o2.alignmentStart;

			return (int) (o1.containerStartOffset - o2.containerStartOffset);
		}
	};

	private static boolean intersect(Entry e0, Entry e1) {
		if (e0.sequenceId != e1.sequenceId)
			return false;
		if (e0.sequenceId < 0)
			return false;

		int a0 = e0.alignmentStart;
		int a1 = e1.alignmentStart;

		int b0 = a0 + e0.alignmentSpan;
		int b1 = a1 + e1.alignmentSpan;

		boolean result = Math.abs(a0 + b0 - a1 - b1) < (e0.alignmentSpan + e1.alignmentSpan);
		return result;

	}

	public static List<Entry> find(List<Entry> list, int seqId, int start, int span) {
		boolean whole = start < 1 || span < 1;
		Entry query = new Entry();
		query.sequenceId = seqId;
		query.alignmentStart = start < 1 ? 1 : start;
		query.alignmentSpan = span < 1 ? Integer.MAX_VALUE : span;
		query.containerStartOffset = Long.MAX_VALUE;
		query.sliceOffset = Integer.MAX_VALUE;
		query.sliceSize = Integer.MAX_VALUE;

		List<Entry> l = new ArrayList<Entry>();
		for (Entry e : list) {
			if (e.sequenceId != seqId) {
				continue;
			}
			if (whole || intersect(e, query))
				l.add(e);
		}
		Collections.sort(l, byStart);
		return l;
	}

	public static Entry getLeftmost(List<Entry> list) {
		if (list == null || list.isEmpty())
			return null;
		Entry left = list.get(0);

		for (Entry e : list)
			if (e.alignmentStart < left.alignmentStart)
				left = e;

		return left;
	}

	private static int findLastAlignedEntry(List<Entry> list) {
		int low = 0;
		int high = list.size() - 1;

		while (low <= high) {
			int mid = (low + high) >>> 1;
			Entry midVal = list.get(mid);

			if (midVal.sequenceId >= 0)
				low = mid + 1;
			else
				high = mid - 1;
		}
		return low;
	}
}
